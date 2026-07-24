package com.sandeshx.services

import com.sandeshx.models.MessageDto
import com.sandeshx.models.Users
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/** MYRA is a normal Users row (isBot = true) with a reserved, unguessable phone
 *  number — every account automatically "has" MYRA available to chat with by
 *  looking her up via GET /api/bots/myra, no phone lookup or invite needed. */
object MyraBotService {
    private const val MYRA_PHONE = "+MYRA0000000AI"
    private const val MYRA_NAME = "MYRA AI"

    @Volatile
    private var cachedUserId: Long? = null

    fun ensureBotUser() {
        transaction {
            val existing = Users.selectAll().where { Users.phoneNumber eq MYRA_PHONE }.singleOrNull()
            cachedUserId = existing?.get(Users.id)?.value ?: Users.insertAndGetId {
                it[phoneNumber] = MYRA_PHONE
                it[displayName] = MYRA_NAME
                it[isBot] = true
                it[createdAt] = Instant.now()
            }.value
        }
    }

    fun botUserId(): Long = cachedUserId
        ?: error("MyraBotService.ensureBotUser() must run at startup before this is called")

    /** Every AI call goes through here: use the requesting user's own Gemini
     *  key if they've set one in Settings, otherwise fall back to the shared
     *  server key (GEMINI_API_KEY). Returns null if neither is available. */
    private fun resolveApiKey(requesterUserId: Long?): String? {
        if (requesterUserId != null) {
            val encrypted = transaction {
                Users.selectAll().where { Users.id eq requesterUserId }.singleOrNull()
                    ?.get(Users.geminiApiKeyEncrypted)
            }
            val ownKey = encrypted?.let { com.sandeshx.security.CryptoUtil.decrypt(it) }
            if (!ownKey.isNullOrBlank()) return ownKey
        }
        return System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
    }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private const val SYSTEM_PROMPT =
        "You are MYRA, a friendly, concise AI assistant built into the SandeshX messaging app. " +
            "Keep replies short and conversational, like a chat message, not an essay. " +
            "Reply in the same language/script the user writes in (Hindi, Hinglish, or English)."

    /** Calls Gemini with recent conversation history for context and returns a reply.
     *  Falls back to a friendly, honest message on any failure (missing API key,
     *  network error, rate limit) instead of leaving the user with no response at all. */
    suspend fun generateReply(history: List<MessageDto>, myraBotUserId: Long, newUserMessage: String, requesterUserId: Long): String =
        withContext(Dispatchers.IO) {
            val apiKey = resolveApiKey(requesterUserId)
            if (apiKey.isNullOrBlank()) {
                return@withContext "MYRA isn't fully set up yet — no Gemini API key is available. " +
                    "Add one for yourself in Settings, or ask whoever runs this SandeshX server to set GEMINI_API_KEY."
            }

            val model = System.getenv("GEMINI_MODEL")?.takeIf { it.isNotBlank() } ?: "gemini-2.5-flash"

            val contents = buildJsonArray {
                for (msg in history.takeLast(10)) {
                    if (msg.deleted) continue
                    val text = msg.body?.takeIf { it.isNotBlank() } ?: continue
                    add(
                        buildJsonObject {
                            put("role", if (msg.senderId == myraBotUserId) "model" else "user")
                            putJsonArray("parts") { addJsonObject { put("text", text) } }
                        }
                    )
                }
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { addJsonObject { put("text", newUserMessage) } }
                    }
                )
            }

            val requestBody = buildJsonObject {
                putJsonObject("system_instruction") {
                    putJsonArray("parts") { addJsonObject { put("text", SYSTEM_PROMPT) } }
                }
                put("contents", contents)
            }.toString()

            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()

            try {
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    println("[MyraBotService] Gemini API error ${response.statusCode()}: ${response.body()}")
                    return@withContext "MYRA is having trouble reaching its AI brain right now (error ${response.statusCode()}). Try again in a bit."
                }
                val parsed = json.parseToJsonElement(response.body()).jsonObject
                val text = parsed["candidates"]?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject
                    ?.get("parts")?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("text")?.jsonPrimitive?.content
                text?.trim()?.takeIf { it.isNotBlank() } ?: "MYRA didn't have a reply for that — try rephrasing?"
            } catch (e: Exception) {
                println("[MyraBotService] Gemini call failed: ${e.message}")
                "MYRA couldn't reach the AI service just now. Please try again in a moment."
            }
        }

    class MyraAiUnavailableException(message: String) : Exception(message)

    /** One-shot Gemini call with no conversation history — used by rewrite/grammar-fix/
     *  summarize/smart-replies, which each just need a single instruction + input text.
     *  Throws [MyraAiUnavailableException] on any failure so routes can surface a clear
     *  error instead of silently returning garbage. */
    private suspend fun callGeminiOnce(systemPrompt: String, userText: String, requesterUserId: Long?): String =
        withContext(Dispatchers.IO) {
            val apiKey = resolveApiKey(requesterUserId)
                ?: throw MyraAiUnavailableException("MYRA isn't set up yet — add your own Gemini API key in Settings, or ask the server admin to set one.")
            val model = System.getenv("GEMINI_MODEL")?.takeIf { it.isNotBlank() } ?: "gemini-2.5-flash"

            val requestBody = buildJsonObject {
                putJsonObject("system_instruction") {
                    putJsonArray("parts") { addJsonObject { put("text", systemPrompt) } }
                }
                putJsonArray("contents") {
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { addJsonObject { put("text", userText) } }
                    }
                }
            }.toString()

            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()

            val response = try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                throw MyraAiUnavailableException("Couldn't reach MYRA's AI service. Check your connection and try again.")
            }
            if (response.statusCode() !in 200..299) {
                println("[MyraBotService] Gemini API error ${response.statusCode()}: ${response.body()}")
                throw MyraAiUnavailableException("MYRA's AI service is temporarily unavailable (error ${response.statusCode()}).")
            }
            val text = runCatching {
                json.parseToJsonElement(response.body()).jsonObject["candidates"]
                    ?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject
                    ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("text")?.jsonPrimitive?.content
            }.getOrNull()
            text?.trim()?.takeIf { it.isNotBlank() }
                ?: throw MyraAiUnavailableException("MYRA didn't return a usable response — try again.")
        }

    /** Up to 3 short suggested replies to the other person's most recent message,
     *  one per line from Gemini — the chat UI shows these as tappable chips above
     *  the input box. Returns an empty list (never throws) so a flaky AI call
     *  never blocks the person from just typing their own reply. */
    suspend fun smartReplies(history: List<MessageDto>, myUserId: Long): List<String> {
        val recentText = history.takeLast(6)
            .mapNotNull { msg -> msg.body?.takeIf { it.isNotBlank() && !msg.deleted } }
        if (recentText.isEmpty()) return emptyList()

        val prompt = "Conversation so far (oldest to newest):\n" + recentText.joinToString("\n") { "- $it" } +
            "\n\nSuggest 3 short, natural replies the user could send next. " +
            "One per line, no numbering, no quotes, max ~8 words each."

        return try {
            val raw = callGeminiOnce(
                "You write extremely short, casual chat-reply suggestions — like Gboard's smart replies. " +
                    "Match the language/script of the conversation (Hindi, Hinglish, or English). Output only the 3 suggestions, one per line.",
                prompt,
                myUserId
            )
            raw.lines().map { it.trim().removePrefix("-").trim() }.filter { it.isNotBlank() }.take(3)
        } catch (e: MyraAiUnavailableException) {
            emptyList()
        }
    }

    suspend fun rewrite(text: String, style: String, requesterUserId: Long): String {
        val styleInstruction = when (style.lowercase()) {
            "formal" -> "Rewrite this message in a formal, professional tone, keeping the original meaning and language."
            "funny" -> "Rewrite this message to be playful and funny, keeping the original meaning and language."
            "professional" -> "Rewrite this message in clear, professional workplace language, keeping the original meaning and language."
            else -> "Rewrite this message to sound more polished, keeping the original meaning and language."
        }
        return callGeminiOnce(
            "$styleInstruction Reply with ONLY the rewritten message text, no preamble, no quotes, no explanation.",
            text,
            requesterUserId
        )
    }

    suspend fun fixGrammar(text: String, requesterUserId: Long): String =
        callGeminiOnce(
            "Fix the spelling and grammar of this message, keeping the same language, tone, and meaning. " +
                "Reply with ONLY the corrected text, no preamble, no quotes, no explanation.",
            text,
            requesterUserId
        )

    suspend fun summarize(history: List<MessageDto>, peerName: String, requesterUserId: Long): String {
        val lines = history.takeLast(60).mapNotNull { msg -> msg.body?.takeIf { it.isNotBlank() && !msg.deleted } }
        if (lines.isEmpty()) return "Nothing to summarize yet — this chat has no text messages."
        return callGeminiOnce(
            "Summarize this chat conversation in 3-5 short bullet points covering the key topics and any " +
                "decisions or action items. Match the language/script used in the conversation. " +
                "Reply with ONLY the bullet points, no preamble.",
            "Conversation with $peerName:\n" + lines.joinToString("\n"),
            requesterUserId
        )
    }

    // --- Bring-your-own-key management ---

    fun setUserApiKey(userId: Long, apiKey: String): Boolean {
        val encrypted = com.sandeshx.security.CryptoUtil.encrypt(apiKey) ?: return false
        transaction {
            Users.update({ Users.id eq userId }) { it[geminiApiKeyEncrypted] = encrypted }
        }
        return true
    }

    fun clearUserApiKey(userId: Long) {
        transaction {
            Users.update({ Users.id eq userId }) { it[geminiApiKeyEncrypted] = null }
        }
    }

    fun hasUserApiKey(userId: Long): Boolean = transaction {
        Users.selectAll().where { Users.id eq userId }.singleOrNull()?.get(Users.geminiApiKeyEncrypted) != null
    }
}
