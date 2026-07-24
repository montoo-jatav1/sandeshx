package com.sandeshx.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts sensitive per-user fields (currently: a user's own Gemini API key)
 * before they touch the database. Uses AES-256-GCM with a server-side secret
 * from the `FIELD_ENCRYPTION_SECRET` env var — never store that secret in
 * source control, same rule as JWT_SECRET.
 */
object CryptoUtil {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val IV_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128

    private val secretKey: SecretKeySpec? by lazy {
        val raw = System.getenv("FIELD_ENCRYPTION_SECRET")
        if (raw.isNullOrBlank()) return@lazy null
        // Accept any passphrase length — hash it down to a stable 32-byte AES key
        // rather than requiring the operator to generate exactly-32-byte base64.
        val keyBytes = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        SecretKeySpec(keyBytes, "AES")
    }

    fun isConfigured(): Boolean = secretKey != null

    /** Returns null (instead of throwing) if FIELD_ENCRYPTION_SECRET isn't set —
     *  callers should treat that as "this feature isn't available yet". */
    fun encrypt(plainText: String): String? {
        val key = secretKey ?: return null
        val iv = ByteArray(IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = iv + cipherBytes
        return Base64.getEncoder().encodeToString(combined)
    }

    fun decrypt(encoded: String): String? {
        val key = secretKey ?: return null
        return try {
            val combined = Base64.getDecoder().decode(encoded)
            val iv = combined.copyOfRange(0, IV_LENGTH_BYTES)
            val cipherBytes = combined.copyOfRange(IV_LENGTH_BYTES, combined.size)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
