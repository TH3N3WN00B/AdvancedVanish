package me.quantiom.advancedvanish

import me.quantiom.advancedvanish.command.VanishCommand
import me.quantiom.advancedvanish.config.Config
import me.quantiom.advancedvanish.hook.HooksManager
import me.quantiom.advancedvanish.listener.VanishListener
import me.quantiom.advancedvanish.permission.PermissionsManager
import me.quantiom.advancedvanish.state.VanishStateManager
import me.quantiom.advancedvanish.sync.ServerSyncManager
import me.quantiom.advancedvanish.util.AdvancedVanishAPI
import me.quantiom.advancedvanish.util.UpdateChecker
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.incendo.cloud.execution.ExecutionCoordinator
import org.incendo.cloud.paper.LegacyPaperCommandManager
import java.util.logging.Level

object AdvancedVanish {
    var instance: AdvancedVanishPlugin? = null
    var commandManager: LegacyPaperCommandManager<CommandSender>? = null

    /** The plugin instance; fails with a clear message instead of an NPE if used outside the plugin lifecycle. */
    val plugin: AdvancedVanishPlugin
        get() = checkNotNull(instance) { "AdvancedVanish is not enabled" }

    fun log(level: Level, msg: String) {
        instance?.logger?.log(level, msg)
    }

    fun onEnable(plugin: AdvancedVanishPlugin) {
        instance = plugin

        Config.reload()

        commandManager = LegacyPaperCommandManager.createNative(
            plugin,
            ExecutionCoordinator.simpleCoordinator()
        ).also { VanishCommand.register(it) }

        // update checker
        if (Config.getValueOrDefault("check-for-updates", true)) {
            UpdateChecker.getVersion {
                if (it != null && it != plugin.pluginMeta.version) {
                    plugin.logger.info("A new update for AdvancedVanish (v${it}) is available:")
                    plugin.logger.info("https://www.spigotmc.org/resources/advancedvanish.86036/")
                }
            }
        }

        plugin.server.pluginManager.registerEvents(ServerSyncManager, plugin)
        plugin.server.pluginManager.registerEvents(VanishListener, plugin)

        PermissionsManager.setupPermissionsHandler()
        HooksManager.setupHooks()
    }

    fun onDisable() {
        VanishStateManager.onDisable()

        // unvanish while hooks and the sync store are still alive, without pushing the
        // shutdown-time state to other servers
        ServerSyncManager.shuttingDown = true
        AdvancedVanishAPI.vanishedPlayers.toList().mapNotNull(Bukkit::getPlayer).forEach { AdvancedVanishAPI.unVanishPlayer(it, true) }

        HooksManager.disableHooks()
        ServerSyncManager.close()
        instance = null
    }
}