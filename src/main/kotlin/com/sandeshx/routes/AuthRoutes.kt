package com.sandeshx.routes

import com.sandeshx.models.*
import com.sandeshx.services.AuthService
import com.sandeshx.services.OtpInvalidException
import com.sandeshx.services.OtpRateLimitException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authRoutes() {
    route("/api/auth") {
        post("/otp/send") {
            val req = call.receive<SendOtpRequest>()
            try {
                AuthService.sendOtp(req.phoneNumber)
                call.respond(HttpStatusCode.OK, mapOf("message" to "OTP sent"))
            } catch (e: OtpRateLimitException) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse(e.message ?: "Rate limited"))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid request"))
            }
        }

        post("/otp/verify") {
            val req = call.receive<VerifyOtpRequest>()
            try {
                val result = AuthService.verifyOtpAndLogin(req.phoneNumber, req.code)
                call.respond(
                    HttpStatusCode.OK,
                    AuthResponse(result.accessToken, result.refreshToken, result.isNewUser, result.userId)
                )
            } catch (e: OtpInvalidException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid code"))
            } catch (e: OtpRateLimitException) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse(e.message ?: "Rate limited"))
            }
        }

        post("/refresh") {
            val req = call.receive<RefreshRequest>()
            try {
                val decoded = com.sandeshx.security.JwtConfig.verifier.verify(req.refreshToken)
                val type = decoded.getClaim("type").asString()
                if (type != "refresh") {
                    return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Not a refresh token"))
                }
                val userId = decoded.getClaim("userId").asLong()
                call.respond(
                    HttpStatusCode.OK,
                    AuthResponse(
                        accessToken = com.sandeshx.security.JwtConfig.generateAccessToken(userId),
                        refreshToken = com.sandeshx.security.JwtConfig.generateRefreshToken(userId),
                        isNewUser = false,
                        userId = userId
                    )
                )
            } catch (e: Exception) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Refresh token invalid or expired — please log in again"))
            }
        }
    }
}
