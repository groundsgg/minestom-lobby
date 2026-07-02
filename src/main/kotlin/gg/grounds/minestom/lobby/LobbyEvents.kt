package gg.grounds.minestom.lobby

import net.minestom.server.coordinate.Pos
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.instance.InstanceContainer
import org.slf4j.LoggerFactory

internal object LobbyEvents {
    private val logger = LoggerFactory.getLogger(LobbyEvents::class.java)

    fun register(eventNode: EventNode<Event>, instanceContainer: InstanceContainer) {
        eventNode.addListener<AsyncPlayerConfigurationEvent>(
            AsyncPlayerConfigurationEvent::class.java
        ) { event: AsyncPlayerConfigurationEvent ->
            val player = event.player

            event.spawningInstance = instanceContainer
            player.respawnPoint = Pos(0.0, 40.0, 0.0)
        }

        eventNode.addListener<PlayerBlockBreakEvent> { it.isCancelled = true }

        eventNode.addListener<AsyncPlayerConfigurationEvent> {
            logger.info(
                "Player joined lobby (playerId={}, username={})",
                it.player.uuid,
                it.player.username,
            )
        }

        eventNode.addListener<PlayerDisconnectEvent> {
            logger.info(
                "Player left lobby (playerId={}, username={})",
                it.player.uuid,
                it.player.username,
            )
        }
    }
}

private inline fun <reified T : Event> EventNode<Event>.addListener(noinline event: (T) -> Unit) {
    this.addListener<T>(T::class.java, event)
}
