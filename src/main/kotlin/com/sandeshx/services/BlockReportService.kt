package com.sandeshx.services

import com.sandeshx.models.BlockedUserDto
import com.sandeshx.models.BlockedUsers
import com.sandeshx.models.Reports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

object BlockService {

    suspend fun block(userId: Long, blockedUserId: Long) = withContext(Dispatchers.IO) {
        transaction {
            val already = BlockedUsers.selectAll()
                .where { (BlockedUsers.userId eq userId) and (BlockedUsers.blockedUserId eq blockedUserId) }
                .any()
            if (!already) {
                BlockedUsers.insert {
                    it[BlockedUsers.userId] = userId
                    it[BlockedUsers.blockedUserId] = blockedUserId
                    it[blockedAt] = Instant.now()
                }
            }
        }
    }

    suspend fun unblock(userId: Long, blockedUserId: Long) = withContext(Dispatchers.IO) {
        transaction {
            BlockedUsers.deleteWhere {
                Op.build { (BlockedUsers.userId eq userId) and (BlockedUsers.blockedUserId eq blockedUserId) }
            }
        }
    }

    suspend fun blockedByMe(userId: Long): List<BlockedUserDto> = withContext(Dispatchers.IO) {
        transaction {
            BlockedUsers.selectAll().where { BlockedUsers.userId eq userId }
                .map { BlockedUserDto(userId = it[BlockedUsers.blockedUserId], blockedAt = it[BlockedUsers.blockedAt].epochSecond) }
        }
    }

    /** True if either side has blocked the other — used to reject a message server-side
     *  rather than just hiding it client-side, per the spec. */
    suspend fun isBlockedEitherWay(userId: Long, otherUserId: Long): Boolean = withContext(Dispatchers.IO) {
        transaction {
            BlockedUsers.selectAll().where {
                ((BlockedUsers.userId eq userId) and (BlockedUsers.blockedUserId eq otherUserId)) or
                    ((BlockedUsers.userId eq otherUserId) and (BlockedUsers.blockedUserId eq userId))
            }.any()
        }
    }
}

object ReportService {
    suspend fun submit(reporterId: Long, reportedId: Long, reason: String) = withContext(Dispatchers.IO) {
        transaction {
            Reports.insert {
                it[Reports.reporterId] = reporterId
                it[Reports.reportedId] = reportedId
                it[Reports.reason] = reason
                it[createdAt] = Instant.now()
            }
        }
    }
}
