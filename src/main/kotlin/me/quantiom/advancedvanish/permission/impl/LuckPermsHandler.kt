package me.quantiom.advancedvanish.permission.impl

import me.quantiom.advancedvanish.config.Config
import me.quantiom.advancedvanish.permission.IPermissionsHandler
import net.luckperms.api.LuckPermsProvider
import org.bukkit.entity.Player

class LuckPermsHandler : IPermissionsHandler {
    override fun getVanishPriority(player: Player): Int {
        val user = LuckPermsProvider.get().userManager.getUser(player.uniqueId) ?: return 0

        val value = user.cachedData.metaData.getMetaValue(Config.getValueOrDefault(
            "priority.meta-key",
            "advancedvanish-priority"
        ))

        return value?.toIntOrNull() ?: 0
    }
}