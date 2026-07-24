package com.sandeshx.routes

import com.sandeshx.models.*
import com.sandeshx.services.MessageService
import com.sandeshx.services.MyraBotService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import com.sandeshx.models.Users

/** MYRA's "AI assist" toolbar in any chat — smart replies, rewrite, grammar
 *  fix, and chat summarization. Separate from the MYRA *bot chat* (which
 *  lives in WebSocketRoutes) — these are one-shot REST calls triggered by
 *  UI buttons, not real-time conversation turns. */
fun Route.myraAiRoutes() {
    authenticate("auth-jwt") {
        route("/api/myra") {
            post("/smart-replies") {
                val me = call.currentUserId()
                val req = call.receive<SmartRepliesRequest>()
                val history = MessageService.history(me, req.peerId, limit = 6)
                val suggestions = MyraBotService.smartReplies(history, me)
                call.respond(SmartRepliesResponse(suggestions))
            }

            post("/rewrite") {
                val me = call.currentUserId()
                val req = call.receive<RewriteRequest>()
                if (req.text.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("text is required"))
                }
                try {
                    val result = MyraBotService.rewrite(req.text, req.style, me)
                    call.respond(RewriteResponse(result))
                } catch (e: MyraBotService.MyraAiUnavailableException) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(e.message ?: "MYRA is unavailable"))
                }
            }

            post("/grammar-fix") {
                val me = call.currentUserId()
                val req = call.receive<GrammarFixRequest>()
                if (req.text.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("text is required"))
                }
                try {
                    val result = MyraBotService.fixGrammar(req.text, me)
                    call.respond(GrammarFixResponse(result))
                } catch (e: MyraBotService.MyraAiUnavailableException) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(e.message ?: "MYRA is unavailable"))
                }
            }

            post("/summarize") {
                val me = call.currentUserId()
                val req = call.receive<SummarizeRequest>()
                val history = MessageService.history(me, req.peerId, limit = 60)
                val peerRow = transaction { Users.selectAll().where { Users.id eq req.peerId }.singleOrNull() }
                val peerName = peerRow?.get(Users.displayName) ?: peerRow?.get(Users.phoneNumber) ?: "them"
                try {
                    val summary = MyraBotService.summarize(history, peerName, me)
                    call.respond(SummarizeResponse(summary))
                } catch (e: MyraBotService.MyraAiUnavailableException) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(e.message ?: "MYRA is unavailable"))
                }
            }

            // --- Bring your own Gemini API key (Settings screen) ---
            get("/my-key-status") {
                val me = call.currentUserId()
                call.respond(MyraKeyStatusResponse(hasCustomKey = MyraBotService.hasUserApiKey(me)))
            }

            put("/my-key") {
                val me = call.currentUserId()
                val req = call.receive<SetMyraKeyRequest>()
                val key = req.apiKey.trim()
                if (!key.startsWith("AIza") || key.length < 30) {
                    return@put call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("That doesn't look like a Gemini API key — real ones start with \"AIza\". Get one free at aistudio.google.com/apikey")
                    )
                }
                val saved = MyraBotService.setUserApiKey(me, key)
                if (!saved) {
                    return@put call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("This server hasn't enabled personal API keys yet (missing FIELD_ENCRYPTION_SECRET).")
                    )
                }
                call.respond(MyraKeyStatusResponse(hasCustomKey = true))
            }

            delete("/my-key") {
                val me = call.currentUserId()
                MyraBotService.clearUserApiKey(me)
                call.respond(MyraKeyStatusResponse(hasCustomKey = false))
            }
        }
    }
}
