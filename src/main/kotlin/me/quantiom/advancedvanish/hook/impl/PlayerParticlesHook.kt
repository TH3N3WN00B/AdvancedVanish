package me.quantiom.advancedvanish.hook.impl

import dev.esophose.playerparticles.api.PlayerParticlesAPI
import me.quantiom.advancedvanish.AdvancedVanish
import me.quantiom.advancedvanish.hook.IHook
import me.quantiom.advancedvanish.util.AdvancedVanishAPI
import org.bukkit.scheduler.BukkitRunnable

class PlayerParticlesHook : IHook {
    override fun getID() = "PlayerParticles"

    private val updateTask: BukkitRunnable =
        object : BukkitRunnable() {
            override fun run() {
                if (AdvancedVanishAPI.vanishedPlayers.isEmpty()) return

                val api = PlayerParticlesAPI.getInstance()

                for (uuid in AdvancedVanishAPI.vanishedPlayers) {
                    api.getPPlayer(uuid)?.activeParticles?.clear()
                }
            }
        }

    override fun onEnable() {
        this.updateTask.runTaskTimer(AdvancedVanish.plugin, 0L, 20L)
    }

    override fun onDisable() {
        this.updateTask.cancel()
    }
}