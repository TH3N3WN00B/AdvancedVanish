package me.quantiom.advancedvanish.sync

import me.quantiom.advancedvanish.AdvancedVanish
import me.quantiom.advancedvanish.config.Config
import me.quantiom.advancedvanish.event.PlayerUnVanishEvent
import me.quantiom.advancedvanish.event.PlayerVanishEvent
import me.quantiom.advancedvanish.sync.impl.RedisServerSyncStore
import me.quantiom.advancedvanish.sync.impl.SqlServerSyncStore
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerLoginEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.logging.Level

object ServerSyncManager : Listener {
    /** Upper bound for pending login states, so a flood of pre-logins can never grow the map without limit. */
    private const val MAX_PENDING_LOGIN_STATES = 2048

    @Volatile
    var crossServerSupportEnabled: Boolean = false

    @Volatile
    var shuttingDown: Boolean = false

    var serverSyncStoreImpl: IServerSyncStore? = null

    val loginVanishStates: MutableMap<UUID, Boolean> = ConcurrentHashMap()

    // writes go through a single thread so a vanish followed by an unvanish can never be stored out of order
    private var writer: ExecutorService? = null

    @Synchronized
    fun setup() {
        // always tear down whatever was set up before (config reload), so pools/connections never leak
        this.close()

        if (!Config.getValueOrDefault("cross-server-support.enabled", false)) {
            this.loginVanishStates.clear()
            return
        }

        val store = when (Config.getValueOrDefault("cross-server-support.mode", "redis").lowercase()) {
            "redis" -> RedisServerSyncStore
            "sql" -> SqlServerSyncStore
            else -> {
                AdvancedVanish.log(Level.WARNING, "Invalid mode set for cross-server-support. Please double check your config.")
                return
            }
        }

        if (store.setup()) {
            this.serverSyncStoreImpl = store
            this.writer = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "AdvancedVanish-Sync").also { it.isDaemon = true }
            }
            this.crossServerSupportEnabled = true
        }
    }

    @Synchronized
    fun close() {
        this.crossServerSupportEnabled = false

        this.writer?.let {
            it.shutdown()

            try {
                if (!it.awaitTermination(3, TimeUnit.SECONDS)) it.shutdownNow()
            } catch (_: InterruptedException) {
                it.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
        this.writer = null

        this.serverSyncStoreImpl?.close()
        this.serverSyncStoreImpl = null
    }

    @EventHandler
    fun onAsyncJoin(event: AsyncPlayerPreLoginEvent) {
        if (!this.crossServerSupportEnabled) return

        if (this.loginVanishStates.size >= MAX_PENDING_LOGIN_STATES) this.loginVanishStates.clear()
        this.loginVanishStates[event.uniqueId] = this.get(event.uniqueId)
    }

    // players that get denied after the pre-login stage never reach the join event, drop their state here
    @EventHandler(priority = EventPriority.MONITOR)
    fun onLogin(event: PlayerLoginEvent) {
        if (event.result != PlayerLoginEvent.Result.ALLOWED) this.loginVanishStates.remove(event.player.uniqueId)
    }

    @EventHandler(ignoreCancelled = true)
    fun onVanish(event: PlayerVanishEvent) {
        if (!event.onJoin) this.setAsync(event.player.uniqueId, true)
    }

    @EventHandler(ignoreCancelled = true)
    fun onUnVanish(event: PlayerUnVanishEvent) {
        if (!event.onLeave) this.setAsync(event.player.uniqueId, false)
    }

    fun get(key: UUID): Boolean {
        if (!this.crossServerSupportEnabled) return false
        return this.serverSyncStoreImpl?.get(key) ?: false
    }

    private fun setAsync(key: UUID, value: Boolean) {
        if (!this.crossServerSupportEnabled || this.shuttingDown) return

        val store = this.serverSyncStoreImpl ?: return

        try {
            this.writer?.execute { store.set(key, value) }
        } catch (_: RejectedExecutionException) {
            // the executor is being shut down, nothing left to do
        }
    }
}
