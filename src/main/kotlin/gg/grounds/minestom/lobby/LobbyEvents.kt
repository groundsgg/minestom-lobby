package gg.grounds.minestom.lobby

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import net.kyori.adventure.text.Component
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
    private val noSceneReady = CompletableFuture.completedFuture<Void>(null)

    internal fun awaitSceneAdmission(
        player: net.minestom.server.entity.Player,
        sceneReady: CompletableFuture<Void>,
        sceneClosed: () -> Boolean,
    ): Boolean {
        try {
            if (sceneReady.isDone) {
                sceneReady.getNow(null)
            } else {
                check(Thread.currentThread().isVirtual) {
                    "Scene admission may wait only on the configuration virtual thread"
                }
                sceneReady.get(30, TimeUnit.SECONDS)
            }
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            logger.warn(
                "Lobby scene admission denied (playerId={}): {}",
                player.uuid,
                failure.toString(),
            )
            if (player.isOnline)
                player.kick(Component.text("Lobby is not ready. Please reconnect."))
            return false
        }
        // A successful future cannot be revoked when shutdown begins.
        if (sceneClosed() || !player.isOnline) {
            if (player.isOnline)
                player.kick(Component.text("Lobby is shutting down. Please reconnect."))
            return false
        }
        return true
    }

    fun register(
        eventNode: EventNode<Event>,
        instanceContainer: InstanceContainer,
        spawn: Pos,
        sceneReady: CompletableFuture<Void> = noSceneReady,
        sceneClosed: () -> Boolean = { false },
    ) {
        eventNode.addListener<AsyncPlayerConfigurationEvent>(
            AsyncPlayerConfigurationEvent::class.java
        ) { event: AsyncPlayerConfigurationEvent ->
            val player = event.player

            // Preserve the old immediate no-scene path, including no service access or wait.
            if (
                sceneReady !== noSceneReady && !awaitSceneAdmission(player, sceneReady, sceneClosed)
            ) {
                event.spawningInstance = null
                return@addListener
            }

            event.spawningInstance = instanceContainer
            player.respawnPoint = spawn
        }

        eventNode.addListener<PlayerBlockBreakEvent> { it.isCancelled = true }

        eventNode.addListener<AsyncPlayerConfigurationEvent> {
            if (it.spawningInstance !== instanceContainer) return@addListener
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
