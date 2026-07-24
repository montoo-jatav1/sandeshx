package com.sandeshx.services

import com.sandeshx.config.RedisFactory
import org.mindrot.jbcrypt.BCrypt
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.TimeUnit

class OtpRateLimitException(message: String) : RuntimeException(message)
class OtpInvalidException(message: String) : RuntimeException(message)

object OtpService {
    private val random = SecureRandom()
    private val OTP_TTL: Duration = Duration.ofMinutes(5)
    private const val MAX_VERIFY_ATTEMPTS = 5
    private const val MAX_SENDS_PER_HOUR = 5

    private fun otpKey(phone: String) = "otp:code:$phone"
    private fun attemptsKey(phone: String) = "otp:attempts:$phone"
    private fun sendCountKey(phone: String) = "otp:sendcount:$phone"

    fun generateAndStore(phoneNumber: String): String {
        RedisFactory.pool.resource.use { redis ->
            val sends = redis.incr(sendCountKey(phoneNumber))
            if (sends == 1L) redis.expire(sendCountKey(phoneNumber), TimeUnit.HOURS.toSeconds(1))
            if (sends > MAX_SENDS_PER_HOUR) {
                throw OtpRateLimitException("Too many OTP requests. Try again later.")
            }

            val code = (100000 + random.nextInt(900000)).toString() // 6-digit
            val hashed = BCrypt.hashpw(code, BCrypt.gensalt(10))
            redis.setex(otpKey(phoneNumber), OTP_TTL.toSeconds(), hashed)
            redis.del(attemptsKey(phoneNumber))
            return code // returned only so the SMS-sending layer can dispatch it; never logged, never returned to the client
        }
    }

    fun verify(phoneNumber: String, candidateCode: String) {
        RedisFactory.pool.resource.use { redis ->
            val storedHash = redis.get(otpKey(phoneNumber))
                ?: throw OtpInvalidException("Code expired or not requested. Request a new one.")

            val attempts = redis.incr(attemptsKey(phoneNumber))
            redis.expire(attemptsKey(phoneNumber), OTP_TTL.toSeconds())
            if (attempts > MAX_VERIFY_ATTEMPTS) {
                redis.del(otpKey(phoneNumber))
                throw OtpRateLimitException("Too many incorrect attempts. Request a new code.")
            }

            if (!BCrypt.checkpw(candidateCode, storedHash)) {
                throw OtpInvalidException("Incorrect code.")
            }

            // consumed — one-time use only
            redis.del(otpKey(phoneNumber))
            redis.del(attemptsKey(phoneNumber))
        }
    }
}
