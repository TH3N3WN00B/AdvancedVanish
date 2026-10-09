package me.quantiom.advancedvanish.util

import me.quantiom.advancedvanish.AdvancedVanish
import me.quantiom.advancedvanish.config.Config
import me.quantiom.advancedvanish.event.PlayerUnVanishEvent
import me.quantiom.advancedvanish.event.PlayerVanishEvent
import me.quantiom.advancedvanish.event.PrePlayerUnVanishEvent
import me.quantiom.advancedvanish.event.PrePlayerVanishEvent
import me.quantiom.advancedvanish.permission.PermissionsManager
import me.quantiom.advancedvanish.state.VanishStateManager
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Registry
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

fun Player.isVanished() = AdvancedVanishAPI.isPlayerVanished(this)

object AdvancedVanishAPI {
    /** Insertion-ordered, duplicate-free and safe to read from async threads (chat, server list ping). */
    val vanishedPlayers: MutableSet<UUID> = Collections.newSetFromMap(ConcurrentHashMap<UUID, Boolean>())
    private val storedPotionEffects: MutableMap<UUID, List<PotionEffect>> = ConcurrentHashMap()

    private const val MAX_EFFECT_AMPLIFIER = 255

    /**
     * Vanishes a player if the PrePlayerVanishEvent
     * does not get cancelled.
     *
     * @param player The player to vanish
     * @param onJoin If this is being called from the PlayerJoinEvent, used for hook/fake join and leave message functionality
     */
    fun vanishPlayer(player: Player, onJoin: Boolean = false) {
        if (this.isPlayerVanished(player)) return

        val plugin = AdvancedVanish.plugin
        val prePlayerVanishEvent = PrePlayerVanishEvent(player, onJoin)
        Bukkit.getPluginManager().callEvent(prePlayerVanishEvent)

        if (prePlayerVanishEvent.isCancelled) return

        this.vanishedPlayers.add(player.uniqueId)

        // add vanished metadata to player for other plugins to use
        player.setMetadata("vanished", FixedMetadataValue(plugin, true))

        val previousEffects: MutableList<PotionEffect> = mutableListOf()

        // add potion effects
        Config.getValueOrDefault("when-vanished.give-potion-effects", emptyList<String>())
            .forEach { raw ->
                val split = raw.split(":")
                if (split.size < 2) return@forEach

                val effectType = Registry.EFFECT.stream().filter { it.name == split[0].trim() }.findFirst().orElse(null) ?: return@forEach
                val amplifier = split[1].trim().toIntOrNull()?.minus(1)?.coerceIn(0, MAX_EFFECT_AMPLIFIER) ?: return@forEach

                val currentPotionEffect = player.activePotionEffects.find { e -> e.type == effectType }

                if (currentPotionEffect != null) {
                    previousEffects.add(currentPotionEffect)
                } else {
                    previousEffects.add(effectType.createEffect(0, 0))
                }

                if (onJoin) {
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        if (player.isOnline && player.isVanished()) player.addPotionEffect(effectType.createEffect(-1, amplifier))
                    }, 10L)
                } else {
                    player.addPotionEffect(effectType.createEffect(-1, amplifier))
                }
            }

        if (previousEffects.isNotEmpty()) {
            this.storedPotionEffects[player.uniqueId] = previousEffects
        }

        val usePriority = Config.usingPriorities && PermissionsManager.handler != null
        val playerPriority = PermissionsManager.handler?.getVanishPriority(player) ?: 0
        val vanishPermission = Config.getValueOrDefault(
            "permissions.vanish",
            "advancedvanish.vanish"
        )

        Bukkit.getOnlinePlayers()
            .filter { it.uniqueId != player.uniqueId }
            .forEach {
                if (usePriority && it.hasPermission(vanishPermission)) {
                    val pPriority = PermissionsManager.handler?.getVanishPriority(it) ?: 0

                    if (pPriority < playerPriority) {
                        it.hidePlayer(plugin, player)
                    }
                } else {
                    it.hidePlayer(plugin, player)
                }
            }

        if (!onJoin && Config.getValueOrDefault("join-leave-messages.fake-leave-message-on-vanish.enable", false)) {
            val message = Config.getValueOrDefault(
                "join-leave-messages.fake-leave-message-on-vanish.message",
                "<yellow>%player-name% has left the game."
            ).applyPlaceholders(
                "%player-name%" to player.name
            )

            Bukkit.getOnlinePlayers().forEach { it.sendMessage(it.renderMessage(message)) }
        }

        if (Config.getValueOrDefault("when-vanished.fly.enable", true)) {
            player.allowFlight = true
        }

        Bukkit.getPluginManager().callEvent(PlayerVanishEvent(player, onJoin))
    }

    /**
     * Vanishes a player if the PrePlayerUnVanishEvent
     * does not get cancelled.
     *
     * @param player The player to unvanish
     * @param onLeave If this is being called from the PlayerQuitEvent, used for hook/fake join and leave message functionality
     */
    fun unVanishPlayer(player: Player, onLeave: Boolean = false) {
        if (!this.isPlayerVanished(player)) return

        val plugin = AdvancedVanish.plugin
        val prePlayerUnVanishEvent = PrePlayerUnVanishEvent(player, onLeave)
        Bukkit.getPluginManager().callEvent(prePlayerUnVanishEvent)

        // a leaving player must always be removed, otherwise they would stay in the vanished set forever
        if (prePlayerUnVanishEvent.isCancelled && !onLeave) return

        this.vanishedPlayers.remove(player.uniqueId)

        // remove vanished metadata from player
        player.removeMetadata("vanished", plugin)

        VanishStateManager.interactEnabled.remove(player.uniqueId)

        this.storedPotionEffects[player.uniqueId]?.let {
            for (potionEffect in it) {
                player.removePotionEffect(potionEffect.type)

                if (potionEffect.duration != 0) {
                    player.addPotionEffect(potionEffect)
                }
            }

            this.storedPotionEffects.remove(player.uniqueId)
        }

        Bukkit.getOnlinePlayers()
            .forEach {
                it.showPlayer(plugin, player)
            }

        // ignore if they are in spectator mode (allowed to fly by default)
        if (player.gameMode != GameMode.SPECTATOR && !player.hasPermission(Config.getValueOrDefault("permissions.keep-fly-on-unvanish", "advancedvanish.keep-fly"))
            && !Config.getValueOrDefault("when-vanished.fly.keep-on-unvanish", false)) {
            player.isFlying = false
            player.allowFlight = false
        }

        if (!onLeave && Config.getValueOrDefault("join-leave-messages.fake-join-message-on-unvanish.enable", false)) {
            val message = Config.getValueOrDefault(
                "join-leave-messages.fake-join-message-on-unvanish.message",
                "<yellow>%player-name% has joined the game."
            ).applyPlaceholders(
                "%player-name%" to player.name
            )

            Bukkit.getOnlinePlayers().forEach { it.sendMessage(it.renderMessage(message)) }
        }

        Bukkit.getPluginManager().callEvent(PlayerUnVanishEvent(player, onLeave))
    }

    fun refreshVanished(player: Player) {
        this.vanishedPlayers.forEach { uuid ->
            Bukkit.getPlayer(uuid)?.let {
                if (!this.canSee(player, it)) {
                    player.hidePlayer(AdvancedVanish.plugin, it)
                }
            }
        }
    }

    /**
     * Checks if a player is vanished
     *
     * @param player The player to check if vanished
     */
    fun isPlayerVanished(player: Player): Boolean = this.vanishedPlayers.contains(player.uniqueId)

    /**
     * Returns true if `player` can see `target`
     *
     * @param player
     * @param target
     */
    fun canSee(player: Player, target: Player): Boolean {
        if (!target.isVanished()) return false

        if (!player.hasPermission(Config.getValueOrDefault(
                "permissions.vanish",
                "advancedvanish.vanish"
            ))) return false

        if (!Config.usingPriorities) return true

        val handler = PermissionsManager.handler ?: return true

        return handler.getVanishPriority(player) >= handler.getVanishPriority(target)
    }
}