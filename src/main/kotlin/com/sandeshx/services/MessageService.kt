package com.sandeshx.services

import com.sandeshx.models.MessageStatus
import com.sandeshx.models.Messages
import com.sandeshx.models.MessageDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

object MessageService {

    private fun rowToDto(row: ResultRow): MessageDto {
        val isDeleted = row[Messages.deleted]
        return MessageDto(
            id = row[Messages.id].value,
            senderId = row[Messages.senderId],
            receiverId = row[Messages.receiverId],
            body = if (isDeleted) null else row[Messages.body],
            imageUrl = if (isDeleted) null else row[Messages.imageUrl],
            status = row[Messages.status].name,
            createdAt = row[Messages.createdAt].epochSecond,
            readAt = row[Messages.readAt]?.epochSecond,
            edited = row[Messages.editedAt] != null,
            deleted = isDeleted
        )
    }

    suspend fun send(senderId: Long, receiverId: Long, body: String?, imageUrl: String?): MessageDto =
        withContext(Dispatchers.IO) {
            require(!body.isNullOrBlank() || !imageUrl.isNullOrBlank()) { "Message must have text or an image" }
            transaction {
                val id = Messages.insertAndGetId {
                    it[Messages.senderId] = senderId
                    it[Messages.receiverId] = receiverId
                    it[Messages.body] = body
                    it[Messages.imageUrl] = imageUrl
                    it[Messages.status] = MessageStatus.SENT
                    it[Messages.createdAt] = Instant.now()
                }
                rowToDto(Messages.selectAll().where { Messages.id eq id }.single())
            }
        }

    /** Two people can message each other in either direction, so the "conversation" filter is:
     *  (sender=A AND receiver=B) OR (sender=B AND receiver=A). Built entirely inside the
     *  `.where { }` DSL scope — eq/and/or are only resolvable there in modern Exposed. */
    suspend fun history(userA: Long, userB: Long, limit: Int = 50, beforeId: Long? = null): List<MessageDto> =
        withContext(Dispatchers.IO) {
            transaction {
                var query = Messages.selectAll().where {
                    ((Messages.senderId eq userA) and (Messages.receiverId eq userB)) or
                        ((Messages.senderId eq userB) and (Messages.receiverId eq userA))
                }
                if (beforeId != null) {
                    query = query.andWhere { Messages.id less beforeId }
                }
                query.orderBy(Messages.id, SortOrder.DESC).limit(limit).map(::rowToDto).reversed()
            }
        }

    /** Recent chats: the latest message per peer this user has exchanged messages with,
     *  newest conversation first. Grouped in Kotlin (not SQL) since we only expect a modest
     *  number of messages per user at this scale, and it keeps the query simple/portable. */
    suspend fun conversations(userId: Long, scanLimit: Int = 500): List<MessageDto> =
        withContext(Dispatchers.IO) {
            transaction {
                val rows = Messages.selectAll().where {
                    (Messages.senderId eq userId) or (Messages.receiverId eq userId)
                }.orderBy(Messages.id, SortOrder.DESC).limit(scanLimit).map(::rowToDto)

                rows.groupBy { if (it.senderId == userId) it.receiverId else it.senderId }
                    .values
                    .map { messagesWithPeer -> messagesWithPeer.first() } // already newest-first
                    .sortedByDescending { it.createdAt }
            }
        }

    /** Only the original sender can edit their own message, and a deleted message can't be
     *  un-deleted by editing it. Returns null (no-op) if either check fails, or the message
     *  doesn't exist, so the route layer can turn that into a clean 403/404 instead of
     *  silently succeeding on someone else's message. */
    suspend fun edit(messageId: Long, requesterId: Long, newBody: String): MessageDto? = withContext(Dispatchers.IO) {
        transaction {
            val row = Messages.selectAll().where { Messages.id eq messageId }.singleOrNull() ?: return@transaction null
            if (row[Messages.senderId] != requesterId || row[Messages.deleted]) return@transaction null
            Messages.update({ Messages.id eq messageId }) {
                it[body] = newBody
                it[editedAt] = Instant.now()
            }
            rowToDto(Messages.selectAll().where { Messages.id eq messageId }.single())
        }
    }

    /** "Delete for everyone": the row stays (so message ids / ordering don't shift under
     *  anyone), but body/image are wiped and `deleted` is set so both sides render a
     *  "This message was deleted" placeholder instead of the original content. */
    suspend fun deleteForEveryone(messageId: Long, requesterId: Long): MessageDto? = withContext(Dispatchers.IO) {
        transaction {
            val row = Messages.selectAll().where { Messages.id eq messageId }.singleOrNull() ?: return@transaction null
            if (row[Messages.senderId] != requesterId) return@transaction null
            Messages.update({ Messages.id eq messageId }) {
                it[body] = null
                it[imageUrl] = null
                it[deleted] = true
            }
            rowToDto(Messages.selectAll().where { Messages.id eq messageId }.single())
        }
    }

    suspend fun markDelivered(messageId: Long) = withContext(Dispatchers.IO) {
        transaction {
            Messages.update({ (Messages.id eq messageId) and (Messages.status eq MessageStatus.SENT) }) {
                it[status] = MessageStatus.DELIVERED
                it[deliveredAt] = Instant.now()
            }
        }
    }

    suspend fun markRead(messageId: Long, readerId: Long): MessageDto? = withContext(Dispatchers.IO) {
        transaction {
            Messages.update({ (Messages.id eq messageId) and (Messages.receiverId eq readerId) }) {
                it[status] = MessageStatus.READ
                it[readAt] = Instant.now()
            }
            Messages.selectAll().where { Messages.id eq messageId }.singleOrNull()?.let(::rowToDto)
        }
    }

    /** Every user id this person has ever exchanged a message with — used to
     *  decide who should be told when this person goes online/offline. */
    suspend fun conversationPeerIds(userId: Long): Set<Long> = withContext(Dispatchers.IO) {
        transaction {
            Messages.selectAll()
                .where { (Messages.senderId eq userId) or (Messages.receiverId eq userId) }
                .map { row -> if (row[Messages.senderId] == userId) row[Messages.receiverId] else row[Messages.senderId] }
                .toSet()
        }
    }
}
