package com.sandeshx.config

import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig

object RedisFactory {
    val pool: JedisPool by lazy {
        val host = System.getenv("REDIS_HOST") ?: "localhost"
        val port = (System.getenv("REDIS_PORT") ?: "6379").toInt()
        val poolConfig = JedisPoolConfig().apply {
            maxTotal = 32
            maxIdle = 8
        }
        JedisPool(poolConfig, host, port)
    }
}
