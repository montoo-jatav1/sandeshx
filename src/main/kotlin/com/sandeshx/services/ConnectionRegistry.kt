package com.sandeshx.services

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory map of userId -> active WebSocket session(s) on THIS instance.
 *  For multi-instance deployments, back this with Redis Pub/Sub so a message
 *  can be routed to whichever instance holds the recipient's socket. */
object ConnectionRegistry {
    private val sessions = mutableMapOf<Long, MutableSet<DefaultWebSocketServerSession>>()
    private val mutex = Mutex()

    suspend fun register(userId: Long, session: DefaultWebSocketServerSession) {
        mutex.withLock {
            sessions.getOrPut(userId) { mutableSetOf() }.add(session)
        }
    }

    suspend fun unregister(userId: Long, session: DefaultWebSocketServerSession) {
        mutex.withLock {
            sessions[userId]?.remove(session)
            if (sessions[userId]?.isEmpty() == true) sessions.remove(userId)
        }
    }

    suspend fun sessionsFor(userId: Long): List<DefaultWebSocketServerSession> = mutex.withLock {
        sessions[userId]?.toList() ?: emptyList()
    }

    suspend fun isConnected(userId: Long): Boolean = mutex.withLock {
        sessions[userId]?.isNotEmpty() == true
    }
}
