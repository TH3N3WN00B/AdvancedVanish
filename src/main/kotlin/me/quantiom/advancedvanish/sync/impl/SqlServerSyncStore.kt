package me.quantiom.advancedvanish.sync.impl

import me.quantiom.advancedvanish.AdvancedVanish
import me.quantiom.advancedvanish.config.Config
import me.quantiom.advancedvanish.sync.IServerSyncStore
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.*
import java.util.logging.Level

object SqlServerSyncStore : IServerSyncStore {
    private const val TIMEOUT_MILLIS = 5000
    private val hostRegex = Regex("[A-Za-z0-9.\\-:\\[\\]]+")

    private val lock = Any()
    private var connection: Connection? = null
    private var url = ""
    private var username = ""
    private var password = ""

    override fun setup(): Boolean {
        val host = Config.getValueOrDefault("cross-server-support.sql.ip", "127.0.0.1")
        val port = Config.getValueOrDefault("cross-server-support.sql.port", 3306)
        val database = Config.getValueOrDefault("cross-server-support.sql.database", "minecraft")

        // host and database come from the config, never let them inject extra JDBC properties
        if (!host.matches(hostRegex) || port !in 1..65535) {
            AdvancedVanish.log(Level.SEVERE, "Invalid SQL host or port in the config.")
            return false
        }

        synchronized(lock) {
            this.url = "jdbc:mysql://$host:$port/${URLEncoder.encode(database, StandardCharsets.UTF_8)}" +
                    "?connectTimeout=$TIMEOUT_MILLIS&socketTimeout=$TIMEOUT_MILLIS" +
                    "&allowLoadLocalInfile=false&allowMultiQueries=false&allowUrlInLocalInfile=false"
            this.username = Config.getValueOrDefault("cross-server-support.sql.username", "root")
            this.password = Config.getValueOrDefault("cross-server-support.sql.password", "")

            try {
                val conn = this.connect()

                conn.createStatement().use { statement ->
                    statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS advancedvanish (
                            id INT AUTO_INCREMENT PRIMARY KEY,
                            uuid CHAR(36) NOT NULL,
                            state BOOLEAN NOT NULL
                        )
                    """.trimIndent())

                    // older versions had no unique key on `uuid`, so every vanish inserted a new row
                    statement.executeUpdate(
                        "DELETE a FROM advancedvanish a JOIN advancedvanish b ON a.uuid = b.uuid AND a.id < b.id"
                    )

                    try {
                        statement.executeUpdate("ALTER TABLE advancedvanish ADD UNIQUE KEY uq_advancedvanish_uuid (uuid)")
                    } catch (e: SQLException) {
                        // 1061 = duplicate key name, the index already exists
                        if (e.errorCode != 1061) throw e
                    }
                }
            } catch (e: SQLException) {
                AdvancedVanish.log(Level.SEVERE, "There was an error while attempting to make a connection with SQL: ${e.message}")
                this.closeQuietly()
                return false
            }
        }

        AdvancedVanish.log(Level.INFO, "Successfully connected to the SQL database.")
        return true
    }

    override fun close() {
        synchronized(lock) { this.closeQuietly() }
    }

    override fun get(key: UUID): Boolean {
        synchronized(lock) {
            return try {
                this.validConnection().prepareStatement("SELECT state FROM advancedvanish WHERE uuid = ?").use { statement ->
                    statement.setString(1, key.toString())

                    statement.executeQuery().use { resultSet ->
                        if (resultSet.next()) resultSet.getBoolean("state") else false
                    }
                }
            } catch (e: SQLException) {
                AdvancedVanish.log(Level.SEVERE, "There was an error while reading from SQL: ${e.message}")
                false
            }
        }
    }

    override fun set(key: UUID, value: Boolean) {
        synchronized(lock) {
            try {
                this.validConnection()
                    .prepareStatement("INSERT INTO advancedvanish (uuid, state) VALUES (?, ?) ON DUPLICATE KEY UPDATE state = VALUES(state)")
                    .use { statement ->
                        statement.setString(1, key.toString())
                        statement.setBoolean(2, value)
                        statement.executeUpdate()
                    }
            } catch (e: SQLException) {
                AdvancedVanish.log(Level.SEVERE, "There was an error while writing to SQL: ${e.message}")
            }
        }
    }

    private fun connect(): Connection =
        DriverManager.getConnection(this.url, this.username, this.password).also { this.connection = it }

    /** Returns a live connection, reconnecting if the server dropped it (e.g. `wait_timeout`). */
    private fun validConnection(): Connection {
        val current = this.connection

        if (current != null && !current.isClosed && current.isValid(2)) return current

        this.closeQuietly()
        return this.connect()
    }

    private fun closeQuietly() {
        try {
            this.connection?.close()
        } catch (_: SQLException) {
        }

        this.connection = null
    }
}
