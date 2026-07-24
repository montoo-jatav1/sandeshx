package com.sandeshx.services

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import com.sandeshx.models.Users
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.FileInputStream

object NotificationService {
    private var initialized = false

    private fun ensureInit() {
        if (initialized) return
        val credsPath = System.getenv("FIREBASE_CREDENTIALS_PATH")
        if (credsPath == null) {
            println("[NotificationService] FIREBASE_CREDENTIALS_PATH not set — push notifications disabled.")
            return
        }
        val options = FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.fromStream(FileInputStream(credsPath)))
            .build()
        if (FirebaseApp.getApps().isEmpty()) FirebaseApp.initializeApp(options)
        initialized = true
    }

    /** Called when a message is sent to a receiver with no active WebSocket connection. */
    fun notifyNewMessage(receiverId: Long, senderDisplayName: String, preview: String) {
        ensureInit()
        if (!initialized) return

        val token = transaction {
            Users.selectAll().where { Users.id eq receiverId }.singleOrNull()?.get(Users.fcmToken)
        } ?: return

        val message = Message.builder()
            .setToken(token)
            .setNotification(
                Notification.builder()
                    .setTitle(senderDisplayName)
                    .setBody(preview)
                    .build()
            )
            .build()

        runCatching { FirebaseMessaging.getInstance().send(message) }
            .onFailure { println("[NotificationService] Push failed: ${it.message}") }
    }
}
