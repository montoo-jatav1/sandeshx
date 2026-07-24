package com.sandeshx.models

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.javatime.timestamp

object Users : LongIdTable("users") {
    val phoneNumber = varchar("phone_number", 20).uniqueIndex()
    val displayName = varchar("display_name", 80).nullable()
    val avatarUrl = varchar("avatar_url", 500).nullable()
    val lastSeenAt = timestamp("last_seen_at").nullable()
    val isOnline = bool("is_online").default(false)
    val createdAt = timestamp("created_at")
    val fcmToken = varchar("fcm_token", 255).nullable()
    val isBot = bool("is_bot").default(false)
    // Encrypted with CryptoUtil (AES-GCM) — never stored or returned as plaintext.
    // Lets a user optionally use their own Gemini quota instead of the shared
    // server key set via the GEMINI_API_KEY env var.
    val geminiApiKeyEncrypted = varchar("gemini_api_key_encrypted", 500).nullable()
}

enum class MessageStatus { SENT, DELIVERED, READ }

object Messages : LongIdTable("messages") {
    val senderId = long("sender_id").references(Users.id)
    val receiverId = long("receiver_id").references(Users.id)
    val body = text("body").nullable()
    val imageUrl = varchar("image_url", 500).nullable()
    val status = enumerationByName("status", 20, MessageStatus::class).default(MessageStatus.SENT)
    val createdAt = timestamp("created_at")
    val deliveredAt = timestamp("delivered_at").nullable()
    val readAt = timestamp("read_at").nullable()
    val editedAt = timestamp("edited_at").nullable()
    val deleted = bool("deleted").default(false)
}

/** Images (chat photos, avatars) stored directly in Postgres as base64 text.
 *  This replaces MinIO, which was never actually deployed as a separate service —
 *  every upload was silently failing because there was nothing listening at the
 *  presigned MinIO URL the backend was handing out. Storing in Postgres works with
 *  zero extra infrastructure, which matters for a free-tier deployment. */
object MediaFiles : LongIdTable("media_files") {
    val ownerId = long("owner_id").references(Users.id)
    val contentType = varchar("content_type", 100)
    val dataBase64 = text("data_base64")
    val createdAt = timestamp("created_at")
}

object Channels : LongIdTable("channels") {
    val name = varchar("name", 100)
    val description = varchar("description", 500).nullable()
    val ownerId = long("owner_id").references(Users.id)
    val isPrivate = bool("is_private").default(false)
    val avatarUrl = varchar("avatar_url", 500).nullable()
    val createdAt = timestamp("created_at")
}

object ChannelSubscribers : org.jetbrains.exposed.sql.Table("channel_subscribers") {
    val channelId = long("channel_id").references(Channels.id)
    val userId = long("user_id").references(Users.id)
    val subscribedAt = timestamp("subscribed_at")
    override val primaryKey = PrimaryKey(channelId, userId)
}

object ChannelPosts : LongIdTable("channel_posts") {
    val channelId = long("channel_id").references(Channels.id)
    val authorId = long("author_id").references(Users.id)
    val body = text("body").nullable()
    val imageUrl = varchar("image_url", 500).nullable()
    val createdAt = timestamp("created_at")
}

/** Per (user, peer) chat settings — mute, archive, favourite, private nickname, and a
 *  wallpaper for this one conversation that overrides the user's global wallpaper.
 *  Kept out of Users/Messages since it's one-sided (my settings for my view of this
 *  chat, not shared with the other person) and per-pair rather than per-user. */
object ChatPreferences : org.jetbrains.exposed.sql.Table("chat_preferences") {
    val userId = long("user_id").references(Users.id)
    val peerId = long("peer_id").references(Users.id)
    val mutedUntil = timestamp("muted_until").nullable() // null = not muted
    val isFavourite = bool("is_favourite").default(false)
    val isArchived = bool("is_archived").default(false)
    val nicknameForPeer = varchar("nickname_for_peer", 100).nullable()
    val customWallpaperPath = varchar("custom_wallpaper_path", 500).nullable()
    val disappearingDurationSeconds = long("disappearing_duration_seconds").nullable() // null/0 = off
    override val primaryKey = PrimaryKey(userId, peerId)
}

/** A blocked user's messages are rejected server-side (checked in the WebSocket
 *  handler before persisting/delivering) — not just hidden client-side. */
object BlockedUsers : org.jetbrains.exposed.sql.Table("blocked_users") {
    val userId = long("user_id").references(Users.id)
    val blockedUserId = long("blocked_user_id").references(Users.id)
    val blockedAt = timestamp("blocked_at")
    override val primaryKey = PrimaryKey(userId, blockedUserId)
}

object Reports : LongIdTable("reports") {
    val reporterId = long("reporter_id").references(Users.id)
    val reportedId = long("reported_id").references(Users.id)
    val reason = text("reason")
    val createdAt = timestamp("created_at")
}
