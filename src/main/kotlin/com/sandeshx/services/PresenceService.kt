package com.sandeshx.services

import com.sandeshx.config.RedisFactory
import java.time.Instant
import java.util.concurrent.TimeUnit

object PresenceService {
    private const val ONLINE_TTL_SECONDS = 45L // heartbeat/WS ping must refresh before this expires

    private fun onlineKey(userId: Long) = "presence:online:$userId"
    private fun lastSeenKey(userId: Long) = "presence:lastseen:$userId"

    fun markOnline(userId: Long) {
        RedisFactory.pool.resource.use { redis ->
            redis.setex(onlineKey(userId), ONLINE_TTL_SECONDS, "1")
        }
    }

    fun markOffline(userId: Long) {
        RedisFactory.pool.resource.use { redis ->
            redis.del(onlineKey(userId))
            redis.set(lastSeenKey(userId), Instant.now().epochSecond.toString())
        }
    }

    fun isOnline(userId: Long): Boolean = RedisFactory.pool.resource.use { redis ->
        redis.exists(onlineKey(userId))
    }

    fun lastSeen(userId: Long): Long? = RedisFactory.pool.resource.use { redis ->
        redis.get(lastSeenKey(userId))?.toLongOrNull()
    }
}
