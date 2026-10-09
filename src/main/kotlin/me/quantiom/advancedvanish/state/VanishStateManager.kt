package me.quantiom.advancedvanish.state

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import me.quantiom.advancedvanish.AdvancedVanish
import me.quantiom.advancedvanish.config.Config
import me.quantiom.advancedvanish.util.isVanished
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.io.File
import java.lang.reflect.Type
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.logging.Level
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object VanishStateManager {
    // a corrupt or malicious file must never be able to exhaust the heap
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024
    private const val MAX_ENTRIES = 100_000

    private val gson = Gson()
    private val savedVanishStatesType: Type = object : TypeToken<MutableMap<UUID, Boolean>>() {}.type

    val savedVanishStates: MutableMap<UUID, Boolean> = ConcurrentHashMap()
    val interactEnabled: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    fun onConfigReload() {
        if (!Config.getValueOrDefault("keep-vanish-state", false)
            || !Config.getValueOrDefault("keep-vanish-state-persistent", false)
        ) {
            return
        }

        AdvancedVanish.instance?.dataFolder?.let { if (!it.exists()) it.mkdirs() }

        // loaded states are merged with whatever is in memory, in-memory entries (newer) always win

        this.load()
    }

    fun onDisable() {
        if (!Config.getValueOrDefault("keep-vanish-state", false)
            || !Config.getValueOrDefault("keep-vanish-state-persistent", false)
        ) {
            return
        }

        Bukkit.getOnlinePlayers().forEach {
            val isVanished = it.isVanished()

            if (isVanished || it.hasPermission(Config.getValueOrDefault("permissions.vanish", "advancedvanish.vanish"))) {
                this.savedVanishStates[it.uniqueId] = isVanished
            }
        }

        this.save()
    }

    fun canInteract(player: Player): Boolean = player.uniqueId in this.interactEnabled

    private fun getFile(): File = File(AdvancedVanish.plugin.dataFolder, "vanishStates.json")

    private fun save() {
        try {
            val file = this.getFile()
            val tmp = File(file.parentFile, "vanishStates.json.tmp")

            // only players that are actually vanished need to be remembered
            val toSave = this.savedVanishStates.filterValues { it }

            // write to a temp file first so a crash mid-write can never corrupt the existing states
            tmp.writeText(gson.toJson(toSave))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            AdvancedVanish.log(Level.WARNING, "Unable to save vanish states: ${e.message}")
        }
    }

    private fun load() {
        try {
            val file = this.getFile()

            if (!file.isFile || file.length() == 0L) return

            if (file.length() > MAX_FILE_BYTES) {
                AdvancedVanish.log(Level.WARNING, "vanishStates.json is too large, ignoring it.")
                return
            }

            val loaded = gson.fromJson<MutableMap<UUID, Boolean>>(file.readText(), savedVanishStatesType)

            loaded?.entries
                ?.asSequence()
                ?.filter { it.key != null && it.value == true }
                ?.take(MAX_ENTRIES)
                ?.forEach { this.savedVanishStates.putIfAbsent(it.key, true) }
        } catch (e: Exception) {
            AdvancedVanish.log(Level.WARNING, "Unable to load vanish states: ${e.message}")
        }
    }
}