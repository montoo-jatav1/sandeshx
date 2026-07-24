package com.sandeshx.services

import com.sandeshx.models.Users
import com.sandeshx.security.JwtConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

data class AuthResult(val accessToken: String, val refreshToken: String, val isNewUser: Boolean, val userId: Long)

object AuthService {
    private val smsSender: SmsSender = run {
        val accountSid = System.getenv("ACCOUNT_SID")
        val authToken = System.getenv("AUTH_TOKEN")
        val fromNumber = System.getenv("TWILIO_FROM_NUMBER")
        if (!accountSid.isNullOrBlank() && !authToken.isNullOrBlank() && !fromNumber.isNullOrBlank()) {
            TwilioSmsSender(accountSid, authToken, fromNumber)
        } else {
            println("[AuthService] Twilio env vars not fully set (ACCOUNT_SID/AUTH_TOKEN/TWILIO_FROM_NUMBER) — falling back to LogSmsSender. OTPs will only appear in server logs, not real SMS.")
            LogSmsSender()
        }
    }

    private val PHONE_REGEX = Regex("^\\+[1-9][0-9]{7,14}$")

    fun sendOtp(phoneNumber: String) {
        require(PHONE_REGEX.matches(phoneNumber)) { "Invalid phone number format. Use E.164, e.g. +919876543210" }
        val code = OtpService.generateAndStore(phoneNumber)
        smsSender.send(phoneNumber, code)
    }

    suspend fun verifyOtpAndLogin(phoneNumber: String, code: String): AuthResult = withContext(Dispatchers.IO) {
        OtpService.verify(phoneNumber, code) // throws OtpInvalidException / OtpRateLimitException on failure

        transaction {
            val existing = Users.selectAll().where { Users.phoneNumber eq phoneNumber }.singleOrNull()
            val userId = existing?.get(Users.id)?.value ?: Users.insertAndGetId {
                it[Users.phoneNumber] = phoneNumber
                it[Users.createdAt] = Instant.now()
            }.value

            AuthResult(
                accessToken = JwtConfig.generateAccessToken(userId),
                refreshToken = JwtConfig.generateRefreshToken(userId),
                isNewUser = existing == null,
                userId = userId
            )
        }
    }
}
