package com.sandeshx.routes

import com.sandeshx.models.*
import com.sandeshx.services.BlockService
import com.sandeshx.services.ChatPreferencesService
import com.sandeshx.services.ReportService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.chatPreferencesRoutes() {
    authenticate("auth-jwt") {
        route("/api/chat-prefs") {
            // All of my per-conversation settings in one call — the chat list uses this
            // to filter All/Unread/Favourites/Archived entirely client-side.
            get {
                val userId = call.currentUserId()
                call.respond(ChatPreferencesService.all(userId))
            }

            get("/{peerId}") {
                val userId = call.currentUserId()
                val peerId = call.parameters["peerId"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid peerId"))
                call.respond(ChatPreferencesService.get(userId, peerId))
            }

            put("/{peerId}") {
                val userId = call.currentUserId()
                val peerId = call.parameters["peerId"]?.toLongOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid peerId"))
                val req = call.receive<UpdateChatPreferencesRequest>()
                call.respond(ChatPreferencesService.update(userId, peerId, req))
            }
        }
    }
}

fun Route.blockReportRoutes() {
    authenticate("auth-jwt") {
        route("/api/users") {
            get("/blocked") {
                val userId = call.currentUserId()
                call.respond(BlockService.blockedByMe(userId))
            }

            post("/block") {
                val userId = call.currentUserId()
                val req = call.receive<BlockRequest>()
                if (req.userId == userId) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Can't block yourself"))
                }
                BlockService.block(userId, req.userId)
                call.respond(HttpStatusCode.OK)
            }

            post("/unblock") {
                val userId = call.currentUserId()
                val req = call.receive<BlockRequest>()
                BlockService.unblock(userId, req.userId)
                call.respond(HttpStatusCode.OK)
            }
        }

        route("/api/reports") {
            post {
                val userId = call.currentUserId()
                val req = call.receive<ReportRequest>()
                if (req.reason.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Reason is required"))
                }
                ReportService.submit(userId, req.reportedUserId, req.reason)
                call.respond(HttpStatusCode.OK)
            }
        }
    }
}
