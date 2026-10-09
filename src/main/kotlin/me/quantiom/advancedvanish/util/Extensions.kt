package me.quantiom.advancedvanish.util

import me.clip.placeholderapi.PlaceholderAPI
import me.quantiom.advancedvanish.config.Config
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

fun String.applyPlaceholders(vararg pairs: Pair<String, String>): String {
    var intermediate = this

    pairs.forEach { (search, replacement) ->
        intermediate = intermediate.replace(search, replacement)
    }

    return intermediate
}

fun List<String>.applyPlaceholders(vararg pairs: Pair<String, String>): List<String>
        = this.map { it.applyPlaceholders(*pairs) }

private val legacyHexCode = Regex("(?i)[&§]x(?:[&§][0-9a-f]){6}")
private val legacyCode = Regex("(?i)[&§][0-9a-fklmnor]")

private val legacyColorNames = mapOf(
    '0' to "black",
    '1' to "dark_blue",
    '2' to "dark_green",
    '3' to "dark_aqua",
    '4' to "dark_red",
    '5' to "dark_purple",
    '6' to "gold",
    '7' to "gray",
    '8' to "dark_gray",
    '9' to "blue",
    'a' to "green",
    'b' to "aqua",
    'c' to "red",
    'd' to "light_purple",
    'e' to "yellow",
    'f' to "white"
)

private val legacyFormatNames = mapOf(
    'k' to "obfuscated",
    'l' to "bold",
    'm' to "strikethrough",
    'n' to "underlined",
    'o' to "italic",
    'r' to "reset"
)

fun String.convertLegacyCodes(): String {
    if (this.indexOf('&') < 0 && this.indexOf('§') < 0) return this

    val hexReplaced = legacyHexCode.replace(this) { match ->
        val hex = match.value.filter { it != '&' && it != '§' }.drop(1)
        "<#$hex>"
    }

    return legacyCode.replace(hexReplaced) { match ->
        val key = match.value.last().lowercaseChar()
        val name = legacyColorNames[key] ?: legacyFormatNames[key] ?: return@replace match.value
        "<$name>"
    }
}

private val miniMessage = MiniMessage.miniMessage()

/**
 * Makes text typed by a player safe to embed in a config message: MiniMessage tags are escaped (no injected
 * click/hover events) and `%` is dropped so it cannot be expanded as a PlaceholderAPI placeholder.
 */
fun String.escapeUserInput(): String =
    miniMessage.escapeTags(this.take(64)).replace("%", "")

fun String.color(): Component {
    return miniMessage.deserialize(this.convertLegacyCodes())
}

fun Component.colorLegacy(): String {
    return LegacyComponentSerializer.legacyAmpersand().serialize(this)
}

fun CommandSender.renderMessage(message: String): Component {
    var rendered = message

    if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
        (this as? Player)?.let { player ->
            rendered = PlaceholderAPI.setPlaceholders(player, rendered)
        }
    }

    return rendered.color()
}

fun CommandSender.sendConfigMessage(key: String) = Config.sendMessage(this, key)
fun CommandSender.sendConfigMessage(key: String, vararg pairs: Pair<String, String>) = Config.sendMessage(this, key, *pairs)

fun CommandSender.sendCommandHandlerMessage(key: String, default: String, vararg pairs: Pair<String, String>) =
    Config.sendCommandHandlerMessage(this, key, default, *pairs)

fun String.resolvePlayer(): Player? =
    Bukkit.getOnlinePlayers().firstOrNull { it.name.equals(this, ignoreCase = true) }