package com.sandeshx.routes

import com.sandeshx.security.JwtConfig
import com.sandeshx.services.ConnectionRegistry
import com.sandeshx.services.MessageService
import com.sandeshx.services.NotificationService
import com.sandeshx.services.PresenceService
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

@Serializable
private data class WsIncoming(
    val type: String, // "message" | "read" | "typing" | "edit" | "delete"
    val receiverId: Long? = null,
    val body: String? = null,
    val imageUrl: String? = null,
    val messageId: Long? = null,
    val isTyping: Boolean? = null
)

// Flat, explicitly-typed outgoing payloads — one per event `type`, matching
// exactly what the Android client (ChatSocket.kt) parses field-by-field.
//
// NOTE: the previous version of this file built these with
// `Json.encodeToString(mapOf("id" to saved.id, "body" to saved.body, ...))`.
// Mixing Long/String/Boolean values in one `mapOf(...)` makes Kotlin infer
// the map's value type as `Any`, and kotlinx.serialization has no serializer
// for `Any` — so that call threw a SerializationException on every single
// message send, silently killing the WebSocket session before anything
// reached either device. That's why messages never showed up instantly.
@Serializable
private data class WsMessageOut(
    val type: String = "message",
    val id: Long,
    val senderId: Long,
    val receiverId: Long,
    val body: String? = null,
    val imageUrl: String? = null,
    val status: String,
    val createdAt: Long,
    val edited: Boolean = false,
    val deleted: Boolean = false
)

@Serializable
private data class WsEditOut(val type: String = "edited", val id: Long, val body: String?)

@Serializable
private data class WsDeleteOut(val type: String = "deleted", val id: Long)

@Serializable
private data class WsReadOut(val type: String = "read", val messageId: Long, val readAt: Long)

@Serializable
private data class WsTypingOut(val type: String = "typing", val userId: Long, val isTyping: Boolean)

@Serializable
private data class WsPresenceOut(val type: String = "presence", val userId: Long, val isOnline: Boolean, val lastSeenAt: Long? = null)

fun Route.chatWebSocketRoute() {
    webSocket("/ws/chat") {
        // Auth: client sends `?token=<accessToken>` on the WS handshake URL.
        val token = call.request.queryParameters["token"]
        val userId = try {
            JwtConfig.verifier.verify(token).getClaim("userId").asLong()
        } catch (e: Exception) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid or missing token"))
            return@webSocket
        }

        ConnectionRegistry.register(userId, this)
        PresenceService.markOnline(userId)
        broadcastPresence(userId, isOnline = true)

        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                val event = try {
                    Json.decodeFromString<WsIncoming>(text)
                } catch (e: Exception) {
                    continue // ignore malformed frames rather than crashing the socket
                }

                when (event.type) {
                    "message" -> {
                        val receiverId = event.receiverId ?: continue
                        if (com.sandeshx.services.BlockService.isBlockedEitherWay(userId, receiverId)) {
                            continue // silently drop — one of the two has blocked the other
                        }
                        val saved = MessageService.send(userId, receiverId, event.body, event.imageUrl)
                        com.sandeshx.services.ChatPreferencesService.unarchiveOnNewMessage(receiverId, userId)
                        val payload = Json.encodeToString(
                            WsMessageOut.serializer(),
                            WsMessageOut(
                                id = saved.id,
                                senderId = saved.senderId,
                                receiverId = saved.receiverId,
                                body = saved.body,
                                imageUrl = saved.imageUrl,
                                status = saved.status,
                                createdAt = saved.createdAt
                            )
                        )
                        // echo to sender (multi-device sync + so it shows up instantly for the
                        // sender too, not just the receiver) and push to receiver if connected
                        ConnectionRegistry.sessionsFor(userId).forEach { it.send(Frame.Text(payload)) }
                        val receiverSessions = ConnectionRegistry.sessionsFor(receiverId)
                        receiverSessions.forEach { it.send(Frame.Text(payload)) }
                        if (receiverSessions.isNotEmpty()) {
                            MessageService.markDelivered(saved.id)
                        } else {
                            val preview = if (!saved.body.isNullOrBlank()) saved.body else "📷 Photo"
                            val muted = com.sandeshx.services.ChatPreferencesService.get(receiverId, userId).mutedUntil
                                ?.let { it > System.currentTimeMillis() / 1000 } ?: false
                            if (!muted) {
                                NotificationService.notifyNewMessage(receiverId, senderDisplayName = "New message", preview = preview ?: "New message")
                            }
                        }

                        // If this message was sent to MYRA, generate and deliver her reply.
                        // Runs in the background so the user's own message still shows up
                        // instantly rather than waiting on the Gemini API round trip.
                        val userText = saved.body
                        if (receiverId == com.sandeshx.services.MyraBotService.botUserId() && !userText.isNullOrBlank()) {
                            launch {
                                val typingPayload = Json.encodeToString(
                                    WsTypingOut.serializer(),
                                    WsTypingOut(userId = receiverId, isTyping = true)
                                )
                                ConnectionRegistry.sessionsFor(userId).forEach { it.send(Frame.Text(typingPayload)) }

                                val history = MessageService.history(userId, receiverId, limit = 10)
                                val replyText = com.sandeshx.services.MyraBotService.generateReply(history, receiverId, userText, userId)
                                val botMessage = MessageService.send(receiverId, userId, replyText, null)

                                val stopTypingPayload = Json.encodeToString(
                                    WsTypingOut.serializer(),
                                    WsTypingOut(userId = receiverId, isTyping = false)
                                )
                                ConnectionRegistry.sessionsFor(userId).forEach { it.send(Frame.Text(stopTypingPayload)) }

                                val botPayload = Json.encodeToString(
                                    WsMessageOut.serializer(),
                                    WsMessageOut(
                                        id = botMessage.id,
                                        senderId = botMessage.senderId,
                                        receiverId = botMessage.receiverId,
                                        body = botMessage.body,
                                        imageUrl = botMessage.imageUrl,
                                        status = botMessage.status,
                                        createdAt = botMessage.createdAt
                                    )
                                )
                                ConnectionRegistry.sessionsFor(userId).forEach { it.send(Frame.Text(botPayload)) }
                            }
                        }
                    }
                    "read" -> {
                        val messageId = event.messageId ?: continue
                        val updated = MessageService.markRead(messageId, userId) ?: continue
                        val payload = Json.encodeToString(
                            WsReadOut.serializer(),
                            WsReadOut(messageId = updated.id, readAt = updated.readAt ?: 0L)
                        )
                        ConnectionRegistry.sessionsFor(updated.senderId).forEach { it.send(Frame.Text(payload)) }
                    }
                    "typing" -> {
                        val receiverId = event.receiverId ?: continue
                        val payload = Json.encodeToString(
                            WsTypingOut.serializer(),
                            WsTypingOut(userId = userId, isTyping = event.isTyping ?: false)
                        )
                        ConnectionRegistry.sessionsFor(receiverId).forEach { it.send(Frame.Text(payload)) }
                    }
                    "edit" -> {
                        val messageId = event.messageId ?: continue
                        val newBody = event.body?.takeIf { it.isNotBlank() } ?: continue
                        val updated = MessageService.edit(messageId, userId, newBody) ?: continue
                        val payload = Json.encodeToString(WsEditOut.serializer(), WsEditOut(id = updated.id, body = updated.body))
                        ConnectionRegistry.sessionsFor(updated.senderId).forEach { it.send(Frame.Text(payload)) }
                        ConnectionRegistry.sessionsFor(updated.receiverId).forEach { it.send(Frame.Text(payload)) }
                    }
                    "delete" -> {
                        val messageId = event.messageId ?: continue
                        val updated = MessageService.deleteForEveryone(messageId, userId) ?: continue
                        val payload = Json.encodeToString(WsDeleteOut.serializer(), WsDeleteOut(id = updated.id))
                        ConnectionRegistry.sessionsFor(updated.senderId).forEach { it.send(Frame.Text(payload)) }
                        ConnectionRegistry.sessionsFor(updated.receiverId).forEach { it.send(Frame.Text(payload)) }
                    }
                }
            }
        } finally {
            ConnectionRegistry.unregister(userId, this)
            if (!ConnectionRegistry.isConnected(userId)) {
                PresenceService.markOffline(userId)
                broadcastPresence(userId, isOnline = false)
            }
        }
    }
}

/**
 * Tells everyone `userId` has ever exchanged a message with that they just
 * went online/offline, so an open chat screen or the chat list updates its
 * "online" / "last seen" text live instead of only on the next refresh.
 */
private suspend fun DefaultWebSocketServerSession.broadcastPresence(userId: Long, isOnline: Boolean) {
    val peerIds = MessageService.conversationPeerIds(userId)
    if (peerIds.isEmpty()) return

    val lastSeenAt = if (isOnline) null else PresenceService.lastSeen(userId)
    val payload = Json.encodeToString(
        WsPresenceOut.serializer(),
        WsPresenceOut(userId = userId, isOnline = isOnline, lastSeenAt = lastSeenAt)
    )
    for (peerId in peerIds) {
        ConnectionRegistry.sessionsFor(peerId).forEach { session ->
            runCatching { session.send(Frame.Text(payload)) }
        }
    }
}
