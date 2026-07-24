package com.sandeshx.services

import com.sandeshx.models.ChatPreferences
import com.sandeshx.models.ChatPreferencesDto
import com.sandeshx.models.UpdateChatPreferencesRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

object ChatPreferencesService {

    private fun rowToDto(row: ResultRow) = ChatPreferencesDto(
        peerId = row[ChatPreferences.peerId],
        mutedUntil = row[ChatPreferences.mutedUntil]?.epochSecond,
        isFavourite = row[ChatPreferences.isFavourite],
        isArchived = row[ChatPreferences.isArchived],
        nicknameForPeer = row[ChatPreferences.nicknameForPeer],
        customWallpaperPath = row[ChatPreferences.customWallpaperPath],
        disappearingDurationSeconds = row[ChatPreferences.disappearingDurationSeconds]
    )

    /** All of the caller's per-conversation settings in one call, so the app can fetch
     *  once and filter the chat list (Unread/Favourites/Archived) entirely client-side,
     *  per the "no new endpoint needed for filtering" design. */
    suspend fun all(userId: Long): List<ChatPreferencesDto> = withContext(Dispatchers.IO) {
        transaction {
            ChatPreferences.selectAll().where { ChatPreferences.userId eq userId }
                .map { rowToDto(it) }
        }
    }

    suspend fun get(userId: Long, peerId: Long): ChatPreferencesDto = withContext(Dispatchers.IO) {
        transaction {
            ChatPreferences.selectAll()
                .where { (ChatPreferences.userId eq userId) and (ChatPreferences.peerId eq peerId) }
                .singleOrNull()?.let { rowToDto(it) }
                ?: ChatPreferencesDto(peerId = peerId)
        }
    }

    /** Upserts by hand (no native upsert used here to keep this portable across the
     *  Postgres versions this project might run on) — read, then insert or update. */
    suspend fun update(userId: Long, peerId: Long, req: UpdateChatPreferencesRequest): ChatPreferencesDto =
        withContext(Dispatchers.IO) {
            transaction {
                val existing = ChatPreferences.selectAll()
                    .where { (ChatPreferences.userId eq userId) and (ChatPreferences.peerId eq peerId) }
                    .singleOrNull()

                val newMutedUntil = when {
                    req.clearMute -> null
                    req.mutedUntil != null -> Instant.ofEpochSecond(req.mutedUntil)
                    else -> existing?.get(ChatPreferences.mutedUntil)
                }
                val newFavourite = req.isFavourite ?: existing?.get(ChatPreferences.isFavourite) ?: false
                val newArchived = req.isArchived ?: existing?.get(ChatPreferences.isArchived) ?: false
                val newNickname = when {
                    req.clearNickname -> null
                    req.nicknameForPeer != null -> req.nicknameForPeer
                    else -> existing?.get(ChatPreferences.nicknameForPeer)
                }
                val newWallpaper = when {
                    req.clearWallpaper -> null
                    req.customWallpaperPath != null -> req.customWallpaperPath
                    else -> existing?.get(ChatPreferences.customWallpaperPath)
                }
                val newDisappearing = when {
                    req.clearDisappearing -> null
                    req.disappearingDurationSeconds != null -> req.disappearingDurationSeconds
                    else -> existing?.get(ChatPreferences.disappearingDurationSeconds)
                }

                if (existing == null) {
                    ChatPreferences.insert {
                        it[ChatPreferences.userId] = userId
                        it[ChatPreferences.peerId] = peerId
                        it[mutedUntil] = newMutedUntil
                        it[isFavourite] = newFavourite
                        it[isArchived] = newArchived
                        it[nicknameForPeer] = newNickname
                        it[customWallpaperPath] = newWallpaper
                        it[disappearingDurationSeconds] = newDisappearing
                    }
                } else {
                    ChatPreferences.update({ (ChatPreferences.userId eq userId) and (ChatPreferences.peerId eq peerId) }) {
                        it[mutedUntil] = newMutedUntil
                        it[isFavourite] = newFavourite
                        it[isArchived] = newArchived
                        it[nicknameForPeer] = newNickname
                        it[customWallpaperPath] = newWallpaper
                        it[disappearingDurationSeconds] = newDisappearing
                    }
                }

                ChatPreferencesDto(
                    peerId = peerId,
                    mutedUntil = newMutedUntil?.epochSecond,
                    isFavourite = newFavourite,
                    isArchived = newArchived,
                    nicknameForPeer = newNickname,
                    customWallpaperPath = newWallpaper,
                    disappearingDurationSeconds = newDisappearing
                )
            }
        }

    /** New message from an archived contact un-archives it — nobody expects a message
     *  to silently vanish into Archived. Cheap no-op if the pair has no row or isn't archived. */
    suspend fun unarchiveOnNewMessage(userId: Long, peerId: Long) = withContext(Dispatchers.IO) {
        transaction {
            ChatPreferences.update({
                (ChatPreferences.userId eq userId) and (ChatPreferences.peerId eq peerId) and (ChatPreferences.isArchived eq true)
            }) {
                it[isArchived] = false
            }
        }
    }
}
