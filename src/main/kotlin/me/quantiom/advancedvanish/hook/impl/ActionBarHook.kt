package me.quantiom.advancedvanish.hook.impl

import me.quantiom.advancedvanish.AdvancedVanish
import me.quantiom.advancedvanish.config.Config
import me.quantiom.advancedvanish.event.PlayerUnVanishEvent
import me.quantiom.advancedvanish.event.PlayerVanishEvent
import me.quantiom.advancedvanish.hook.IHook
import me.quantiom.advancedvanish.util.AdvancedVanishAPI
import me.quantiom.advancedvanish.util.color
import me.quantiom.advancedvanish.util.renderMessage
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.scheduler.BukkitRunnable

class ActionBarHook : IHook {
    private val updateTask: BukkitRunnable =
        object : BukkitRunnable() {
            override fun run() {
                for (uuid in AdvancedVanishAPI.vanishedPlayers) {
                    Bukkit.getPlayer(uuid)?.let(::sendActionBar)
                }
            }
        }

    override fun getID() = "ActionBar"

    override fun onEnable() {
        this.updateTask.runTaskTimer(AdvancedVanish.plugin, 0L, 30L)
    }

    override fun onDisable() {
        this.updateTask.cancel()
    }

    private fun sendActionBar(player: Player) {
        this.sendActionBarStr(player, Config.getValueOrDefault("messages.action-bar", "<red>You are in vanish."))
    }

    // last raw message without placeholders and its parsed form
    private var cachedRaw: String? = null
    private var cachedComponent: Component? = null

    private fun sendActionBarStr(player: Player, str: String) {
        // only messages without `%` can't depend on the player, those can be parsed once and reused
        if ('%' in str) {
            player.sendActionBar(player.renderMessage(str))
            return
        }

        var component = this.cachedComponent
        if (component == null || this.cachedRaw != str) {
            component = str.color()
            this.cachedRaw = str
            this.cachedComponent = component
        }

        player.sendActionBar(component)
    }

    @EventHandler
    private fun onVanish(event: PlayerVanishEvent) {
        this.sendActionBar(event.player)
    }

    @EventHandler
    private fun onUnVanish(event: PlayerUnVanishEvent) {
        this.sendActionBarStr(event.player, "")
    }
}
