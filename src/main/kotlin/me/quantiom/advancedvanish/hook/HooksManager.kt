package me.quantiom.advancedvanish.hook

import me.quantiom.advancedvanish.AdvancedVanish
import me.quantiom.advancedvanish.config.Config
import me.quantiom.advancedvanish.hook.impl.*
import org.bukkit.Bukkit
import org.bukkit.event.HandlerList
import java.util.logging.Level

object HooksManager {
    private val hooks: MutableList<IHook> = mutableListOf()

    @Suppress("UNCHECKED_CAST")
    fun setupHooks() {
        this.addHook("server-list", "packetevents", "Server List", ::ServerListHook)
        this.addHook("essentials", "Essentials", "Essentials", ::EssentialsHook)
        this.addHook("placeholders", "PlaceholderAPI", "Placeholders", ::PlaceHolderApiHook)
        this.addHook("actionbar", "ActionBarAPI", "Action Bar", ::ActionBarHook)
        this.addHook("discordsrv", "DiscordSRV", "DiscordSRV", ::DiscordSrvHook)
        this.addHook("dynmap", "Dynmap", "Dynmap", ::DynmapHook)
        this.addHook("squaremap", "squaremap", "squaremap", ::SquaremapHook)
        this.addHook("libsdisguises", "LibsDisguises", "LibsDisguises", ::LibsDisguisesHook)
        this.addHook("playerparticles", "PlayerParticles", "PlayerParticles", ::PlayerParticlesHook)

        // a misbehaving hook must never take the whole plugin down with it
        this.hooks.removeAll { hook ->
            try {
                Bukkit.getServer().pluginManager.registerEvents(hook, AdvancedVanish.plugin)
                hook.onEnable()
                false
            } catch (e: Throwable) {
                AdvancedVanish.log(Level.SEVERE, "Unable to enable the ${hook.getID()} hook: ${e}")
                HandlerList.unregisterAll(hook)
                true
            }
        }
    }

    fun disableHooks() {
        this.hooks.forEach {
            try {
                it.onDisable()
            } catch (e: Throwable) {
                AdvancedVanish.log(Level.WARNING, "Unable to disable the ${it.getID()} hook: ${e}")
            }

            HandlerList.unregisterAll(it)
        }
        this.hooks.clear()
    }

    fun reloadHooks() {
        AdvancedVanish.log(Level.INFO, "Reloading hooks...")

        this.disableHooks()
        this.setupHooks()
    }

    fun isHookEnabled(hookID: String): Boolean {
        return this.hooks.find { it.getID() == hookID } != null
    }

    private fun <T> addHook(configOption: String, dependencyName: String, hookName: String, instanciator: () -> (T)) {
        if (Config.getValueOrDefault("hooks.$configOption", false)) {
            if (!Bukkit.getPluginManager().isPluginEnabled(dependencyName) && dependencyName != "ActionBarAPI") {
                AdvancedVanish.log(Level.INFO, "$dependencyName not found, not using the $hookName hook.")
            } else {
                try {
                    this.hooks.add(instanciator() as IHook)
                    AdvancedVanish.log(Level.INFO, "Using $hookName hook.")
                } catch (e: Throwable) {
                    AdvancedVanish.log(Level.SEVERE, "Unable to create the $hookName hook: $e")
                }
            }
        }
    }
}