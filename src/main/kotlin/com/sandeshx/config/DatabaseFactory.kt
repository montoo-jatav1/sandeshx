package com.sandeshx.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import com.sandeshx.models.Users
import com.sandeshx.models.Messages
import com.sandeshx.models.MediaFiles
import com.sandeshx.models.Channels
import com.sandeshx.models.ChannelSubscribers
import com.sandeshx.models.ChannelPosts

object DatabaseFactory {

    fun init() {
        val config = HikariConfig().apply {
            jdbcUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/sandeshx"
            driverClassName = "org.postgresql.Driver"
            username = System.getenv("DATABASE_USER") ?: "sandeshx"
            password = System.getenv("DATABASE_PASSWORD") ?: "sandeshx"
            maximumPoolSize = 10
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        }
        val dataSource = HikariDataSource(config)
        Database.connect(dataSource)

        transaction {
            SchemaUtils.createMissingTablesAndColumns(Users, Messages, MediaFiles, Channels, ChannelSubscribers, ChannelPosts)
        }
    }
}
