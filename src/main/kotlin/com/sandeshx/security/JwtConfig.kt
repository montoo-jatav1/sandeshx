package com.sandeshx.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.util.Date
import java.util.concurrent.TimeUnit

object JwtConfig {
    private val secret = System.getenv("JWT_SECRET")
        ?: error("JWT_SECRET env var is required — refuse to boot with a default secret in prod")
    private val algorithm = Algorithm.HMAC256(secret)
    const val issuer = "sandeshx"
    const val audience = "sandeshx-clients"

    val verifier = JWT.require(algorithm).withIssuer(issuer).build()

    fun generateAccessToken(userId: Long): String = JWT.create()
        .withIssuer(issuer)
        .withAudience(audience)
        .withClaim("userId", userId)
        .withClaim("type", "access")
        .withExpiresAt(Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15)))
        .sign(algorithm)

    fun generateRefreshToken(userId: Long): String = JWT.create()
        .withIssuer(issuer)
        .withAudience(audience)
        .withClaim("userId", userId)
        .withClaim("type", "refresh")
        .withExpiresAt(Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30)))
        .sign(algorithm)
}
