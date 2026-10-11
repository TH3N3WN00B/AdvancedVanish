package me.quantiom.advancedvanish.listener

import io.papermc.paper.event.player.AsyncChatEvent
import me.quantiom.advancedvanish.config.Config
import me.quantiom.advancedvanish.sync.ServerSyncManager
import me.quantiom.advancedvanish.state.VanishStateManager
import me.quantiom.advancedvanish.util.AdvancedVanishAPI
import me.quantiom.advancedvanish.util.isVanished
import me.quantiom.advancedvanish.util.sendConfigMessage
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.block.Chest
import org.bukkit.block.ShulkerBox
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.*

object VanishListener : Listener {
    /** Marks the read-only container copies shown to vanished players, so they cannot be spoofed through a renamed block. */
    private class SilentContainerHolder : InventoryHolder {
        lateinit var backing: Inventory

        override fun getInventory(): Inventory = backing
    }

    @EventHandler
    private fun onJoin(event: PlayerJoinEvent) {
        val player = event.player

        val vanishPermission = Config.getValueOrDefault(
            "permissions.vanish",
            "advancedvanish.vanish"
        )

        val joinVanishedPermission = Config.getValueOrDefault(
            "permissions.join-vanished",
            "advancedvanish.join-vanished"
        )

        var doVanish = false

        val loginVanishState = ServerSyncManager.loginVanishStates.remove(player.uniqueId)

        if (player.hasPermission(vanishPermission)) {
            if (ServerSyncManager.crossServerSupportEnabled && loginVanishState != null) {
                doVanish = loginVanishState
            } else {
                // the state the player had when they left wins over `vanish-on-join`: someone who
                // turned vanish off before leaving does not get vanished again just by rejoining.
                // The join-vanished permission still always applies.
                val savedState = if (Config.getValueOrDefault("keep-vanish-state", false)) {
                    VanishStateManager.savedVanishStates.remove(player.uniqueId)
                } else {
                    null
                }

                doVanish = when (savedState) {
                    true -> true
                    false -> player.hasPermission(joinVanishedPermission)
                    null -> Config.getValueOrDefault("vanish-on-join", false) || player.hasPermission(joinVanishedPermission)
                }
            }
        }

        if (doVanish) {
            AdvancedVanishAPI.vanishPlayer(player, true)
            player.sendConfigMessage("vanish-on")
        }

        AdvancedVanishAPI.refreshVanished(player)

        if (!Config.getValueOrDefault("when-vanished.join-messages", false)) {
            if (AdvancedVanishAPI.isPlayerVanished(player)) {
                event.joinMessage = null
            }
        }
    }

    @EventHandler
    private fun onDisconnect(event: PlayerQuitEvent) {
        val player = event.player
        val isVanished = player.isVanished()

        if (isVanished || player.hasPermission(Config.getValueOrDefault("permissions.vanish", "advancedvanish.vanish"))) {
            if (Config.getValueOrDefault("keep-vanish-state", false)) {
                VanishStateManager.savedVanishStates[player.uniqueId] = isVanished
            }

            if (isVanished) {
                AdvancedVanishAPI.unVanishPlayer(player, true)
            }
        }

        // never keep a leaving player in the vanished set, even if another plugin cancelled the unvanish
        AdvancedVanishAPI.vanishedPlayers.remove(player.uniqueId)
        VanishStateManager.interactEnabled.remove(player.uniqueId)

        if (!Config.getValueOrDefault("when-vanished.leave-messages", false)) {
            if (isVanished) {
                event.quitMessage = null
            }
        }
    }

    @EventHandler
    private fun onChat(event: AsyncChatEvent) =
        genericEventCancel(
            event,
            event.player,
            "when-vanished.send-messages",
            "cannot-chat-while-vanished",
            false
        )

    @EventHandler
    private fun onBlockPlace(event: BlockPlaceEvent) =
        genericEventCancel(
            event,
            event.player,
            "when-vanished.place-blocks",
            "cannot-place-blocks-while-vanished",
            true
        )

    @EventHandler
    private fun onBlockBreak(event: BlockBreakEvent) =
        genericEventCancel(
            event,
            event.player,
            "when-vanished.break-blocks",
            "cannot-break-blocks-while-vanished",
            true
        )

    @EventHandler
    private fun onInteract(event: PlayerInteractEvent) {
        val player = event.player
        val clickedBlock = event.clickedBlock

        if (!player.isVanished()) return

        if (event.action == Action.RIGHT_CLICK_BLOCK && clickedBlock != null && !player.isSneaking
            && !Config.getValueOrDefault("when-vanished.open-and-use-chests", false)
            && this.openContainerSilently(player, clickedBlock)
        ) {
            event.isCancelled = true
            return
        }

        genericEventCancel(
            event,
            event.player,
            "when-vanished.interact",
            "",
            true
        )
    }

    /** Opens a read-only copy of the container. Returns false if the block is not a supported container. */
    private fun openContainerSilently(player: Player, block: org.bukkit.block.Block): Boolean {
        val type = block.type

        val inventoryName: String
        val inventory = when {
            type == Material.CHEST || type == Material.TRAPPED_CHEST -> {
                inventoryName = "Chest"
                (block.state as? Chest)?.inventory
            }
            type == Material.BARREL -> {
                inventoryName = "Barrel"
                (block.state as? Barrel)?.inventory
            }
            type == Material.ENDER_CHEST -> {
                inventoryName = "Enderchest"
                player.enderChest
            }
            type.name.endsWith("SHULKER_BOX") -> {
                inventoryName = "Shulker"
                (block.state as? ShulkerBox)?.inventory
            }
            else -> return false
        } ?: return false

        if (type == Material.ENDER_CHEST) {
            player.openInventory(inventory)
        } else {
            val holder = SilentContainerHolder()
            val cloneInventory = Bukkit.createInventory(holder, inventory.size, net.kyori.adventure.text.Component.text("AdvancedVanish $inventoryName"))
            holder.backing = cloneInventory
            cloneInventory.contents = inventory.contents

            player.openInventory(cloneInventory)
        }

        player.sendConfigMessage("opening-container-silently", "%type%" to inventoryName.lowercase())
        return true
    }

    @EventHandler
    private fun onInventoryClick(event: InventoryClickEvent) {
        if (event.view.topInventory.holder is SilentContainerHolder) event.isCancelled = true
    }

    @EventHandler
    private fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is SilentContainerHolder) event.isCancelled = true
    }

    @EventHandler
    private fun onPickUp(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return

        genericEventCancel(
            event,
            player,
            "when-vanished.pick-up-items",
            "",
            true
        )
    }

    @EventHandler
    private fun onDrop(event: PlayerDropItemEvent) =
        genericEventCancel(
            event,
            event.player,
            "when-vanished.drop-items",
            "cannot-drop-items-while-vanished",
            true
        )

    @EventHandler
    private fun onHungerLoss(event: FoodLevelChangeEvent) {
        if (event.entity is Player) {
            genericEventCancel(
                event,
                event.entity as Player,
                "when-vanished.lose-hunger",
                "",
                false
            )
        }
    }

    @EventHandler
    private fun onEntityTarget(event: EntityTargetLivingEntityEvent) {
        if (event.target is Player) {
            genericEventCancel(
                event,
                event.target as Player,
                "when-vanished.mob-targeting",
                "",
                false
            )
        }
    }

    @EventHandler
    private fun onDamage(event: EntityDamageByEntityEvent) {
        (event.damager as? Player)?.let { damager ->
            if (damager.isVanished() && !Config.getValueOrDefault("when-vanished.attack-entities", false) && !VanishStateManager.canInteract(damager)) {
                damager.sendConfigMessage("cannot-attack-entities-while-vanished")
                event.isCancelled = true
            }
        }

        (event.entity as? Player)?.let { attacked ->
            if (attacked.isVanished() && !Config.getValueOrDefault("when-vanished.receive-damage-from-entities", false)) {
                event.isCancelled = true
            }
        }
    }

    @EventHandler
    private fun onDamage2(event: EntityDamageEvent) {
        // damage from entities is handled (and configurable) in onDamage
        if (event is EntityDamageByEntityEvent) return

        (event.entity as? Player)?.let { player ->
            if (player.isVanished()) {
                event.isCancelled = true
            }
        }
    }

    private fun genericEventCancel(event: Cancellable, player: Player, toggle: String, message: String, ignoreIfCanInteract: Boolean) {
        if (player.isVanished() && !Config.getValueOrDefault(toggle, false)) {
            if (ignoreIfCanInteract && VanishStateManager.canInteract(player)) {
                return
            }

            if (message.isNotEmpty()) player.sendConfigMessage(message)
            event.isCancelled = true
        }
    }
}
