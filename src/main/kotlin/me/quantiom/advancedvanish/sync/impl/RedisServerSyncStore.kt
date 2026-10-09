package me.quantiom.advancedvanish.sync.impl

import me.quantiom.advancedvanish.AdvancedVanish
import me.quantiom.advancedvanish.config.Config
import me.quantiom.advancedvanish.sync.IServerSyncStore
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.time.Duration
import java.util.*
import java.util.logging.Level

object RedisServerSyncStore : IServerSyncStore {
    private const val TIMEOUT_MILLIS = 3000
    private const val KEY_TTL_SECONDS = 90L * 24 * 60 * 60

    @Volatile
    private var pool: JedisPool? = null

    override fun setup(): Boolean {
        val host = Config.getValueOrDefault("cross-server-support.redis.ip", "127.0.0.1")
        val port = Config.getValueOrDefault("cross-server-support.redis.port", 6379)

        val clientConfig = DefaultJedisClientConfig.builder()
            .password(Config.getValueOrDefault("cross-server-support.redis.auth", "").ifEmpty { null })
            .connectionTimeoutMillis(TIMEOUT_MILLIS)
            .socketTimeoutMillis(TIMEOUT_MILLIS)
            .build()

        val poolConfig = JedisPoolConfig().apply {
            maxTotal = 8
            maxIdle = 4
            setMaxWait(Duration.ofMillis(TIMEOUT_MILLIS.toLong()))
        }

        val newPool = JedisPool(poolConfig, HostAndPort(host, port), clientConfig)

        try {
            newPool.resource.use { it.ping() }
        } catch (e: Exception) {
            AdvancedVanish.log(Level.SEVERE, "There was an error while attempting to make a connection with Redis: ${e.message}")
            newPool.close()
            return false
        }

        this.pool = newPool
        AdvancedVanish.log(Level.INFO, "Successfully connected to Redis.")
        return true
    }

    override fun close() {
        this.pool?.close()
        this.pool = null
    }

    override fun get(key: UUID): Boolean {
        val pool = this.pool ?: return false

        return try {
            pool.resource.use { it.get(this.getPlayerKey(key)) } == "true"
        } catch (e: Exception) {
            AdvancedVanish.log(Level.SEVERE, "There was an error while reading from Redis: ${e.message}")
            false
        }
    }

    override fun set(key: UUID, value: Boolean) {
        val pool = this.pool ?: return

        try {
            // states expire so abandoned players do not accumulate in Redis forever
            pool.resource.use { it.setex(this.getPlayerKey(key), KEY_TTL_SECONDS, value.toString()) }
        } catch (e: Exception) {
            AdvancedVanish.log(Level.SEVERE, "There was an error while writing to Redis: ${e.message}")
        }
    }

    private fun getPlayerKey(uuid: UUID) = "advancedvanish-${uuid}"
}
