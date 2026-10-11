package me.quantiom.advancedvanish.hook.impl

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.SimplePacketListenerAbstract
import com.github.retrooper.packetevents.event.simple.PacketStatusSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.status.server.WrapperStatusServerResponse
import com.google.gson.JsonArray
import java.util.UUID
import me.quantiom.advancedvanish.util.AdvancedVanishAPI
import me.quantiom.advancedvanish.hook.IHook

class ServerListHook : IHook {
    private val packetListener = object : SimplePacketListenerAbstract() {
        override fun onPacketStatusSend(event: PacketStatusSendEvent) {
            if (event.packetType != PacketType.Status.Server.RESPONSE) return

            val wrapper = WrapperStatusServerResponse(event)
            // never let a malformed response from another plugin break (or crash) the ping handling
            try {
                val component = wrapper.component ?: return
                val players = component.getAsJsonObject("players") ?: return

                val vanished = AdvancedVanishAPI.vanishedPlayers
                if (vanished.isEmpty()) return

                // adjust online player count
                val online = players.get("online")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                players.addProperty("online", (online - vanished.size).coerceAtLeast(0))

                // remove vanished players from the player sample list
                players.getAsJsonArray("sample")?.let { sample ->
                    val newArray = JsonArray(sample.size())

                    sample.forEach { element ->
                        val id = element.takeIf { it.isJsonObject }?.asJsonObject?.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                        val uuid = id?.let { runCatching { UUID.fromString(it) }.getOrNull() }

                        if (uuid == null || uuid !in vanished) newArray.add(element)
                    }

                    players.add("sample", newArray)
                }

                wrapper.component = component
                event.markForReEncode(true)
            } catch (_: RuntimeException) {
                // leave the response untouched
            }
        }
    }

    override fun getID() = "ServerList"

    override fun onEnable() {
        PacketEvents.getAPI().eventManager.registerListener(packetListener)
    }

    override fun onDisable() {
        PacketEvents.getAPI().eventManager.unregisterListener(packetListener)
    }
}
