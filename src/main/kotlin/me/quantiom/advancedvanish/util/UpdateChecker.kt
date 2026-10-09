package me.quantiom.advancedvanish.util

import me.quantiom.advancedvanish.AdvancedVanish
import org.bukkit.Bukkit
import java.io.IOException
import java.net.URI
import java.util.function.Consumer
import java.util.logging.Level

// credit: https://www.spigotmc.org/wiki/creating-an-update-checker-that-checks-for-updates/

object UpdateChecker {
    private const val RESOURCE_ID = 86036
    private const val TIMEOUT_MILLIS = 5000
    private const val MAX_RESPONSE_BYTES = 64

    fun getVersion(consumer: Consumer<String?>) {
        Bukkit.getScheduler().runTaskAsynchronously(AdvancedVanish.plugin, Runnable {
            try {
                val connection = URI("https://api.spigotmc.org/legacy/update.php?resource=${RESOURCE_ID}").toURL().openConnection()
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS

                // the response is just a version string, never read more than a few bytes of it
                val version = connection.getInputStream().use { it.readNBytes(MAX_RESPONSE_BYTES) }
                    .toString(Charsets.UTF_8)
                    .trim()
                    .takeWhile { !it.isWhitespace() }

                if (version.isNotEmpty() && version.all { it.isLetterOrDigit() || it in ".-_+" }) {
                    consumer.accept(version)
                }
            } catch (exception: IOException) {
                AdvancedVanish.log(Level.INFO, "Unable to check for updates: " + exception.message)
            }
        })
    }
}