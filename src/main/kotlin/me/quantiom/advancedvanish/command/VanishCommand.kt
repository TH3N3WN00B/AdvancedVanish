@file:Suppress("UnstableApiUsage")

package me.quantiom.advancedvanish.command

import me.quantiom.advancedvanish.AdvancedVanish
import me.quantiom.advancedvanish.config.Config
import me.quantiom.advancedvanish.hook.HooksManager
import me.quantiom.advancedvanish.permission.PermissionsManager
import me.quantiom.advancedvanish.state.VanishStateManager
import me.quantiom.advancedvanish.util.*
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.context.CommandInput
import org.incendo.cloud.exception.CommandExecutionException
import org.incendo.cloud.exception.InvalidSyntaxException
import org.incendo.cloud.exception.NoPermissionException
import org.incendo.cloud.exception.NoSuchCommandException
import org.incendo.cloud.exception.handling.ExceptionHandler
import org.incendo.cloud.paper.LegacyPaperCommandManager
import org.incendo.cloud.parser.standard.StringParser
import org.incendo.cloud.permission.PredicatePermission
import org.incendo.cloud.suggestion.BlockingSuggestionProvider
import org.incendo.cloud.suggestion.SuggestionProvider
import java.util.logging.Level

object VanishCommand {
    private val HELP_MESSAGE = listOf(
        "",
        "<red><strikethrough>----------</strikethrough><bold> AdvancedVanish </bold><red><strikethrough>----------",
        "<red>/vanish <dark_gray>- <white>Toggle vanish.",
        "<red>/vanish version <dark_gray>- <white>Shows the version of the plugin.",
        "<red>/vanish reload <dark_gray>- <white>Reloads the config and hooks.",
        "<red>/vanish interact <dark_gray>- <white>Toggles interacting with blocks while in vanish.",
        "<red>/vanish priority <dark_gray>- <white>Displays your vanish priority.",
        "<red>/vanish list <dark_gray>- <white>Displays a list of vanished players.",
        "<red>/vanish status <player> <dark_gray>- <white>Check if a player is in vanish.",
        "<red>/vanish set <player> <on/off> <dark_gray>- <white>Set another player's vanish.",
        "<red>/vanish toggle <player> <dark_gray>- <white>Toggle another player's vanish.",
        "<red><strikethrough>-----------------------------------",
        ""
    )

    private class OnlinePlayersProvider : BlockingSuggestionProvider.Strings<CommandSender> {
        override fun stringSuggestions(context: CommandContext<CommandSender>, input: CommandInput): Iterable<String> =
            Bukkit.getOnlinePlayers().map(Player::getName)
    }

    fun register(commandManager: LegacyPaperCommandManager<CommandSender>) {
        registerExceptionHandlers(commandManager)

        val root = commandManager.commandBuilder("vanish", "v", "advancedvanish")

        commandManager.command(root.permission(configPermission("permissions.vanish", "advancedvanish.vanish"))
            .handler { ctx ->
            val sender = ctx.sender()
            if (sender !is Player) {
                sender.sendPlayersOnlyMessage()
                return@handler
            }

            if (sender.isVanished()) {
                AdvancedVanishAPI.unVanishPlayer(sender)
                sender.sendConfigMessage("vanish-off")
            } else {
                AdvancedVanishAPI.vanishPlayer(sender)
                sender.sendConfigMessage("vanish-on")
            }
        })

        commandManager.command(root.literal("version").permission(configPermission("permissions.version-command", "advancedvanish.version-command"))
            .handler { ctx ->
            val sender = ctx.sender()

            sender.sendConfigMessage("version-command", "%version%" to "v${AdvancedVanish.plugin.pluginMeta.version}")
        })

        commandManager.command(root.literal("reload", "reloadconfig").permission(configPermission("permissions.reload-config-command", "advancedvanish.reload-config-command"))
            .handler { ctx ->
            val sender = ctx.sender()

            Config.reload().also { sender.sendConfigMessage("config-reloaded") }

            HooksManager.reloadHooks()
            PermissionsManager.setupPermissionsHandler()
        })

        commandManager.command(root.literal("interact").permission(configPermission("permissions.interact-command", "advancedvanish.interact-command"))
            .handler { ctx ->
            val sender = ctx.sender()
            if (sender !is Player) {
                sender.sendPlayersOnlyMessage()
                return@handler
            }

            if (!sender.isVanished()) {
                sender.sendConfigMessage("must-be-vanished-to-use-command")
            } else if (VanishStateManager.interactEnabled.contains(sender.uniqueId)) {
                VanishStateManager.interactEnabled.remove(sender.uniqueId)
                sender.sendConfigMessage("vanish-interact-toggled", "%interact-status%" to "off")
            } else {
                VanishStateManager.interactEnabled.add(sender.uniqueId)
                sender.sendConfigMessage("vanish-interact-toggled", "%interact-status%" to "on")
            }
        })

        commandManager.command(root.literal("priority").permission(configPermission("permissions.priority-command", "advancedvanish.priority-command"))
            .handler { ctx ->
            val sender = ctx.sender()
            if (sender !is Player) {
                sender.sendPlayersOnlyMessage()
                return@handler
            }

            if (PermissionsManager.handler == null) {
                sender.sendConfigMessage("not-using-vanish-priority")
            } else {
                val priority = PermissionsManager.handler?.getVanishPriority(sender) ?: 0
                sender.sendConfigMessage("vanish-priority", "%priority%" to priority.toString())
            }
        })

        commandManager.command(root.literal("list").permission(configPermission("permissions.list-command", "advancedvanish.list-command"))
            .handler { ctx ->
            val sender = ctx.sender()

            val players = AdvancedVanishAPI.vanishedPlayers.mapNotNull(Bukkit::getPlayer).joinToString(", ", transform = Player::getName)

            sender.sendConfigMessage("vanished-list", "%vanished-players%" to players.ifEmpty { "None" })
        })

        commandManager.command(root.literal("status")
            .required("player", StringParser.stringParser(), SuggestionProvider.blockingStrings(OnlinePlayersProvider()))
            .permission(configPermission("permissions.status-command", "advancedvanish.status-command"))
            .handler { ctx ->
                val sender = ctx.sender()

                val target = ctx.get<String>("player")
                val targetPlayer = target.resolvePlayer()
                if (targetPlayer == null) {
                    sender.sendCommandHandlerMessage(
                        "could-not-find-player",
                        "Couldn't find a player by the name of <red>%search%<white>.",
                        "%search%" to target.escapeUserInput()
                    )
                    return@handler
                }

                sender.sendConfigMessage("vanish-status-command",
                    "%target-name%" to targetPlayer.name,
                    "%vanish-status%" to if (targetPlayer.isVanished()) "on" else "off",
                    "%vanish-status-word%" to if (targetPlayer.isVanished()) "vanished" else "not vanished"
                )
            })

        commandManager.command(root.literal("set")
            .required("player", StringParser.stringParser(), SuggestionProvider.blockingStrings(OnlinePlayersProvider()))
            .required("status", StringParser.stringParser())
            .permission(configPermission("permissions.set-other-command", "advancedvanish.set-other-command"))
            .handler { ctx ->
                val sender = ctx.sender()

                val target = ctx.get<String>("player")
                val targetPlayer = target.resolvePlayer()
                if (targetPlayer == null) {
                    sender.sendCommandHandlerMessage(
                        "could-not-find-player",
                        "Couldn't find a player by the name of <red>%search%<white>.",
                        "%search%" to target.escapeUserInput()
                    )
                    return@handler
                }

                val toChange = when (ctx.get<String>("status").lowercase()) {
                    "on", "true" -> true
                    "off", "false" -> false
                    else -> {
                        sender.sendCommandHandlerMessage(
                            "invalid-syntax",
                            "Usage: %command% %syntax%",
                            "%command%" to "/vanish",
                            "%syntax%" to "set <player> <on/off>"
                        )
                        return@handler
                    }
                }
                var sendAlready = false

                if (targetPlayer.isVanished()) {
                    if (toChange) {
                        sendAlready = true
                    } else {
                        AdvancedVanishAPI.unVanishPlayer(targetPlayer)
                    }
                } else {
                    if (!toChange) {
                        sendAlready = true
                    } else {
                        AdvancedVanishAPI.vanishPlayer(targetPlayer)
                    }
                }

                if (sendAlready) {
                    sender.sendConfigMessage("vanish-set-other-command-already",
                        "%target-name%" to targetPlayer.name,
                        "%vanish-status%" to if (targetPlayer.isVanished()) "on" else "off",
                        "%vanish-status-word%" to if (targetPlayer.isVanished()) "vanished" else "not vanished"
                    )
                } else {
                    sender.sendConfigMessage("vanish-set-other-command",
                        "%target-name%" to targetPlayer.name,
                        "%vanish-status%" to if (targetPlayer.isVanished()) "on" else "off",
                        "%vanish-status-word%" to if (targetPlayer.isVanished()) "vanished" else "not vanished"
                    )
                }
            })

        commandManager.command(root.literal("toggle")
            .required("player", StringParser.stringParser(), SuggestionProvider.blockingStrings(OnlinePlayersProvider()))
            .permission(configPermission("permissions.toggle-other-command", "advancedvanish.toggle-other-command"))
            .handler { ctx ->
                val sender = ctx.sender()

                val target = ctx.get<String>("player")
                val targetPlayer = target.resolvePlayer()
                if (targetPlayer == null) {
                    sender.sendCommandHandlerMessage(
                        "could-not-find-player",
                        "Couldn't find a player by the name of <red>%search%<white>.",
                        "%search%" to target.escapeUserInput()
                    )
                    return@handler
                }

                if (targetPlayer.isVanished()) {
                    AdvancedVanishAPI.unVanishPlayer(targetPlayer)
                } else {
                    AdvancedVanishAPI.vanishPlayer(targetPlayer)
                }

                sender.sendConfigMessage("vanish-set-other-command",
                    "%target-name%" to targetPlayer.name,
                    "%vanish-status%" to if (targetPlayer.isVanished()) "on" else "off",
                    "%vanish-status-word%" to if (targetPlayer.isVanished()) "vanished" else "not vanished"
                )
            })

        commandManager.command(root.literal("help").permission(configPermission("permissions.help-command", "advancedvanish.help-command"))
            .handler { ctx ->
            val sender = ctx.sender()

            this.HELP_MESSAGE.forEach { sender.sendMessage(it.color()) }
        })
    }

    private fun registerExceptionHandlers(commandManager: LegacyPaperCommandManager<CommandSender>) {
        val exceptionController = commandManager.exceptionController()

        exceptionController.registerHandler(
            NoSuchCommandException::class.java,
            ExceptionHandler<CommandSender, NoSuchCommandException> { context ->
                context.context().sender().sendCommandHandlerMessage("unknown-command", "<red>Invalid arguments.")
            }
        )

        exceptionController.registerHandler(
            InvalidSyntaxException::class.java,
            ExceptionHandler<CommandSender, InvalidSyntaxException> { context ->
                context.context().sender().sendCommandHandlerMessage(
                    "invalid-syntax",
                    "Usage: %command% %syntax%",
                    "%command%" to "/vanish",
                    "%syntax%" to context.exception().correctSyntax()
                )
            }
        )

        exceptionController.registerHandler(
            NoPermissionException::class.java,
            ExceptionHandler<CommandSender, NoPermissionException> { context ->
                context.context().sender().sendConfigMessage("no-permission")
            }
        )

        exceptionController.registerHandler(
            CommandExecutionException::class.java,
            ExceptionHandler<CommandSender, CommandExecutionException> { context ->
                AdvancedVanish.instance?.logger?.log(Level.WARNING, "Error while executing command", context.exception().cause ?: context.exception())
                context.context().sender().sendCommandHandlerMessage("error-performing-command", "<red>There was an error performing this command.")
            }
        )
    }

    private fun CommandSender.sendPlayersOnlyMessage() =
        this.sendMessage("<red>You must be a player to use this command.".color())

    /** A permission whose node is read from the config every time it is checked, so reloads apply immediately. */
    private fun configPermission(key: String, default: String): PredicatePermission<CommandSender> =
        PredicatePermission.of { sender -> sender.hasPermission(Config.getValueOrDefault(key, default)) }
}
