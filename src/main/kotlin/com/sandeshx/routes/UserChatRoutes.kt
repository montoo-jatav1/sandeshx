package com.sandeshx.routes

import com.sandeshx.models.*
import com.sandeshx.services.MessageService
import com.sandeshx.services.PresenceService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import com.sandeshx.models.Users

fun ApplicationCall.currentUserId(): Long =
    (principal<JWTPrincipal>()!!.payload.getClaim("userId").asLong())

fun Route.userRoutes() {
    authenticate("auth-jwt") {
        route("/api/users") {
            get("/me") {
                val userId = call.currentUserId()
                val row = transaction { Users.selectAll().where { Users.id eq userId }.single() }
                call.respond(
                    UserProfileDto(
                        id = userId,
                        phoneNumber = row[Users.phoneNumber],
                        displayName = row[Users.displayName],
                        avatarUrl = row[Users.avatarUrl],
                        isOnline = PresenceService.isOnline(userId),
                        lastSeenAt = PresenceService.lastSeen(userId),
                        isBot = row[Users.isBot]
                    )
                )
            }

            get("/{id}") {
                val targetId = call.parameters["id"]!!.toLong()
                val row = transaction { Users.selectAll().where { Users.id eq targetId }.singleOrNull() }
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                call.respond(
                    UserProfileDto(
                        id = targetId,
                        phoneNumber = row[Users.phoneNumber],
                        displayName = row[Users.displayName],
                        avatarUrl = row[Users.avatarUrl],
                        isOnline = PresenceService.isOnline(targetId),
                        lastSeenAt = PresenceService.lastSeen(targetId),
                        isBot = row[Users.isBot]
                    )
                )
            }
            get("/lookup") {
                val phone = call.request.queryParameters["phone"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("phone query param required"))
                val row = transaction { Users.selectAll().where { Users.phoneNumber eq phone }.singleOrNull() }
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("No SandeshX user with that number"))
                val targetId = row[Users.id].value
                call.respond(
                    UserProfileDto(
                        id = targetId,
                        phoneNumber = row[Users.phoneNumber],
                        displayName = row[Users.displayName],
                        avatarUrl = row[Users.avatarUrl],
                        isOnline = PresenceService.isOnline(targetId),
                        lastSeenAt = PresenceService.lastSeen(targetId)
                    )
                )
            }
            put("/me") {
                val userId = call.currentUserId()
                val req = call.receive<UpdateProfileRequest>()
                transaction {
                    Users.update({ Users.id eq userId }) {
                        if (req.displayName != null) it[displayName] = req.displayName
                        if (req.avatarUrl != null) it[avatarUrl] = req.avatarUrl
                    }
                }
                val row = transaction { Users.selectAll().where { Users.id eq userId }.single() }
                call.respond(
                    UserProfileDto(
                        id = userId,
                        phoneNumber = row[Users.phoneNumber],
                        displayName = row[Users.displayName],
                        avatarUrl = row[Users.avatarUrl],
                        isOnline = PresenceService.isOnline(userId),
                        lastSeenAt = PresenceService.lastSeen(userId)
                    )
                )
            }

            post("/me/fcm-token") {
                val userId = call.currentUserId()
                val body = call.receive<Map<String, String>>()
                val token = body["token"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("token required"))
                transaction {
                    Users.update({ Users.id eq userId }) { it[fcmToken] = token }
                }
                call.respond(HttpStatusCode.OK)
            }
        }

        route("/api/bots") {
            get("/myra") {
                val botId = com.sandeshx.services.MyraBotService.botUserId()
                val row = transaction { Users.selectAll().where { Users.id eq botId }.single() }
                call.respond(
                    UserProfileDto(
                        id = botId,
                        phoneNumber = row[Users.phoneNumber],
                        displayName = row[Users.displayName],
                        avatarUrl = row[Users.avatarUrl],
                        isOnline = true, // MYRA is always "online"
                        lastSeenAt = null,
                        isBot = true
                    )
                )
            }
        }
    }
}

fun Route.chatRoutes() {
    authenticate("auth-jwt") {
        route("/api/chats") {
            get("/conversations") {
                val me = call.currentUserId()
                call.respond(MessageService.conversations(me))
            }

            get("/{userId}/messages") {
                val me = call.currentUserId()
                val other = call.parameters["userId"]!!.toLong()
                val before = call.request.queryParameters["before"]?.toLongOrNull()
                val history = MessageService.history(me, other, beforeId = before)
                call.respond(history)
            }

            post("/{userId}/read/{messageId}") {
                val me = call.currentUserId()
                val messageId = call.parameters["messageId"]!!.toLong()
                val updated = MessageService.markRead(messageId, me)
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                call.respond(updated)
            }
        }
    }
}
