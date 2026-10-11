package me.quantiom.advancedvanish.config

import me.quantiom.advancedvanish.AdvancedVanish
import me.quantiom.advancedvanish.sync.ServerSyncManager
import me.quantiom.advancedvanish.state.VanishStateManager
import me.quantiom.advancedvanish.util.applyPlaceholders
import me.quantiom.advancedvanish.util.color
import me.quantiom.advancedvanish.util.colorLegacy
import me.quantiom.advancedvanish.util.renderMessage
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.*
import java.util.logging.Level

object Config {
    var savedConfig: FileConfiguration? = null
    var usingPriorities = false

    private var CONFIG_VERSION: Int? = null

    // Event handlers query the config on every event; resolving a dotted path through
    // YamlConfiguration each time is wasted work, so resolved values are memoised until the next reload.
    private val NULL_VALUE = Any()

    @PublishedApi
    internal val valueCache = java.util.concurrent.ConcurrentHashMap<String, Any>()

    @PublishedApi
    internal fun lookup(key: String): Any? {
        val cached = this.valueCache[key]
        if (cached != null) return if (cached === NULL_VALUE) null else cached

        val config = this.savedConfig ?: return null
        val value = config.get(key)

        this.valueCache[key] = value ?: NULL_VALUE
        return value
    }

    // get config version from maven variable
    init {
        val p = Properties()

        try {
            this.javaClass.classLoader.getResourceAsStream("app.properties")?.use { p.load(it.buffered()) }
                ?: AdvancedVanish.log(Level.SEVERE, "Unable to find app.properties!")
        } catch (e: IOException) {
            AdvancedVanish.log(Level.SEVERE, "Unable to read app.properties: ${e.message}")
        }

        CONFIG_VERSION = p.getProperty("application.config.version")?.trim()?.toIntOrNull()
    }

    private var messages: MutableMap<String, List<String>> = mutableMapOf()

    fun reload() {
        val instance = AdvancedVanish.plugin

        if (!File(instance.dataFolder, "config.yml").exists()) {
            instance.saveDefaultConfig()
        }

        instance.let {
            it.reloadConfig()
            this.savedConfig = it.config
            this.valueCache.clear()
        }

        // config-version check (a build without a readable version marker leaves the config untouched)
        val targetVersion = CONFIG_VERSION
        val currentConfigVersion = this.getValueOrDefault("config-version", targetVersion ?: 0)
        if (targetVersion != null && currentConfigVersion != targetVersion) {
            File(instance.dataFolder, "config.yml").also {
                if (it.exists()) {
                    val backupFile = File(instance.dataFolder, "config-backup-${System.currentTimeMillis()}.yml")
                    it.copyTo(backupFile)
                    it.delete()
                    instance.saveDefaultConfig()

                    instance.let { plugin ->
                        plugin.reloadConfig()

                        val oldYamlConfig = YamlConfiguration().also { config -> config.load(backupFile) }

                        for (key in oldYamlConfig.getKeys(false)) {
                            checkKeys(currentConfigVersion, targetVersion, plugin.config, oldYamlConfig, key)
                        }

                        if (currentConfigVersion < 8) {
                            AdvancedVanish.log(Level.INFO, "Config messages have been updated to use the MiniMessage formatting. Please check your config.yml for more information.")
                        }

                        (plugin.config as YamlConfiguration).options().width(120)

                        plugin.saveConfig()
                        this.valueCache.clear()
                    }
                }
            }

            AdvancedVanish.log(Level.INFO, "Config version mismatch (most likely because of an update), a backup of the old config has been made and the current config has been replaced.")
            this.reload()
            return
        }

        this.reloadMessages()
        ServerSyncManager.shuttingDown = false
        ServerSyncManager.setup()
        VanishStateManager.onConfigReload()
        this.usingPriorities = this.getValueOrDefault("priority.enable", false)
    }

    private fun checkKeys(oldVersion: Int, newVersion: Int, newYamlConfig: FileConfiguration, oldYamlConfig: YamlConfiguration, currKey: String) {
        if (currKey == "config-version") return

        if (oldYamlConfig.isConfigurationSection(currKey)) {
            for (key in oldYamlConfig.getConfigurationSection(currKey)?.getKeys(false).orEmpty()) {
                checkKeys(oldVersion, newVersion, newYamlConfig, oldYamlConfig, "${currKey}.${key}")
            }
        } else {
            val newValue = newYamlConfig.get(currKey)
            val oldValue = oldYamlConfig.get(currKey)

            if (newValue != null && oldValue != null && newValue::class == oldValue::class) {
                // update formatting to use MiniMessage
                if (newValue is String && oldValue is String && oldVersion < 8) {
                    newYamlConfig.set(currKey, MiniMessage.miniMessage().serialize(LegacyComponentSerializer.legacyAmpersand().deserialize(oldValue)))
                } else {
                    newYamlConfig.set(currKey, oldValue)
                }
            }
        }
    }

    inline fun <reified T> getValue(key: String): T? {
        val value = this.lookup(key)

        if (value is T) {
            return value
        }

        // some default values
        return when (T::class) {
            Boolean::class -> false as T
            String::class -> "" as T
            Int::class -> 0 as T
            Double::class -> 0.0 as T
            else -> null
        }
    }

    inline fun <reified T> getValueOrDefault(key: String, default: T): T {
        val value = this.lookup(key)

        if (value is T) {
            return value
        }

        return default
    }

    private fun getMessage(key: String): List<String> {
        return messages[key] ?: listOf("<gray>Message not found for <red>$key<gray>.")
    }

    private fun getMessage(key: String, vararg pairs: Pair<String, String>): List<String> {
        return messages[key]?.applyPlaceholders(*pairs) ?: listOf("<gray>Message not found for <red>$key<gray>.")
    }

    fun sendMessage(player: CommandSender, key: String) {
        val prefix = this.messagePrefix()

        this.getMessage(key).filter { it.isNotEmpty() }.forEach {
            player.sendMessage(player.renderMessage(prefix + it))
        }
    }

    fun sendMessage(sender: CommandSender, key: String, vararg pairs: Pair<String, String>) {
        val prefix = this.messagePrefix()

        this.getMessage(key, *pairs).filter { it.isNotEmpty() }.forEach {
            sender.sendMessage(sender.renderMessage(prefix + it))
        }
    }

    private fun messagePrefix(): String {
        return if (this.getValueOrDefault("messages.prefix.enabled", false)) {
            this.getValueOrDefault("messages.prefix.value", "<red>[AdvancedVanish]<white> ")
        } else {
            ""
        }
    }

    private fun reloadMessages() {
        messages.clear()

        this.savedConfig?.getConfigurationSection("messages")?.let {
            it.getKeys(false).forEach { key ->
                if (it.isString(key)) {
                    it.getString(key)?.let { value -> messages[key] = listOf(value) }
                } else if (it.isList(key)) {
                    messages[key] = it.getList(key).orEmpty().filterIsInstance<String>()
                }
            }
        }
    }

    fun sendCommandHandlerMessage(sender: CommandSender, key: String, default: String, vararg pairs: Pair<String, String>) {
        val prefix = if (this.savedConfig?.getBoolean("command-handler-messages.use-prefix") == true) {
            this.getValueOrDefault("messages.prefix.value", "<red>[AdvancedVanish]<white> ")
        } else {
            ""
        }

        val message = prefix + (this.savedConfig?.getString("command-handler-messages.$key", default) ?: default)
            .applyPlaceholders(*pairs)

        sender.sendMessage(sender.renderMessage(message))
    }
}