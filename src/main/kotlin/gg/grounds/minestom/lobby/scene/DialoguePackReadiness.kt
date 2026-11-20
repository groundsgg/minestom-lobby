package gg.grounds.minestom.lobby.scene

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.WeakHashMap
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerPluginMessageEvent
import net.minestom.server.network.player.PlayerSocketConnection

/** Reserved proxy channel. Velocity consumes client messages instead of forwarding this channel. */
internal class DialoguePackReadiness(private val apply: (Player, State) -> Unit) {
    private val states = WeakHashMap<Player, State>()

    fun register(events: EventNode<Event>) {
        events.addListener(PlayerPluginMessageEvent::class.java) { event ->
            if (event.identifier == CHANNEL) accept(event.player, event.message)
        }
        events.addListener(PlayerDisconnectEvent::class.java) { event ->
            synchronized(states) { states.remove(event.player) }
        }
    }

    internal fun accept(player: Player, payload: ByteArray) {
        // The proxy stamps this property into its authenticated forwarded game profile.
        val edition =
            (player.playerConnection as? PlayerSocketConnection)
                ?.gameProfile()
                ?.properties()
                ?.firstOrNull { it.name() == "grounds:edition" }
                ?.value()
        if (!allowsGraphics(edition, player.uuid)) return
        val next = decode(player.uuid, payload) ?: return
        synchronized(states) {
            if (next.revision <= (states[player]?.revision ?: 0)) return
            states[player] = next
        }
        MinecraftServer.getSchedulerManager()
            .scheduleNextTick(
                Runnable {
                    if (player.isOnline && synchronized(states) { states[player] === next })
                        apply(player, next)
                }
            )
    }

    data class State(val revision: Long, val ready: Boolean, val pin: String)

    companion object {
        const val CHANNEL = "grounds:dialogue_pack"

        internal fun allowsGraphics(edition: String?, playerId: UUID): Boolean =
            !edition.equals("bedrock", ignoreCase = true) && playerId.mostSignificantBits != 0L

        internal fun decode(playerId: UUID, payload: ByteArray): State? {
            if (payload.size != 90) return null
            val bytes = ByteBuffer.wrap(payload)
            if (bytes.get().toInt() != 1 || UUID(bytes.long, bytes.long) != playerId) return null
            val revision = bytes.long
            val flag = bytes.get().toInt()
            val pin = String(ByteArray(64).also(bytes::get), StandardCharsets.US_ASCII)
            if (revision <= 0 || flag !in 0..1 || !pin.matches(Regex("[0-9a-f]{64}"))) return null
            return State(revision, flag == 1, pin)
        }
    }
}
