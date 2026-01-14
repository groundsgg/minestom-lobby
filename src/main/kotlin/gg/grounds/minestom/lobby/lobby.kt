package gg.grounds.minestom.lobby

import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.event.Event
import net.minestom.server.event.GlobalEventHandler
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.instance.LightingChunk
import net.minestom.server.instance.block.Block
import net.minestom.server.utils.chunk.ChunkSupplier

const val SUNRISE_TIME: Long = 6000

fun main() {
    val minecraftServer = MinecraftServer.init(Auth.Online())
    MinecraftServer.setBrandName("Grounds Lobby")

    val instanceContainer = MinecraftServer.getInstanceManager().createInstanceContainer()

    // Just creates an infinite flat world
    instanceContainer.setGenerator { it.modifier().fillHeight(0, 40, Block.GRASS_BLOCK) }
    instanceContainer.chunkSupplier = ChunkSupplier { instance, x, y ->
        LightingChunk(instance, x, y)
    }

    // Disable day-night cycle
    instanceContainer.timeRate = 0
    instanceContainer.time = SUNRISE_TIME

    val globalEventHandler = MinecraftServer.getGlobalEventHandler()

    globalEventHandler.addListener<AsyncPlayerConfigurationEvent>(
        AsyncPlayerConfigurationEvent::class.java
    ) { event: AsyncPlayerConfigurationEvent ->
        val player = event.player

        event.spawningInstance = instanceContainer
        player.respawnPoint = Pos(0.0, 40.0, 0.0)
    }

    // The server must explicitly forbid to break blocks
    globalEventHandler.addListener<PlayerBlockBreakEvent> { it.isCancelled = true }

    // Apparently, default Minestom has Chat Support.
    // If desired, chat messages can be canceled with the line below
    // globalEventHandler.addListener<PlayerChatEvent> { it.isCancelled = true }

    globalEventHandler.addListener<AsyncPlayerConfigurationEvent> {
        println("${it.player.uuid}/${it.player.username} joined the server")
    }

    minecraftServer.start("0.0.0.0", 25565)
    println("Started Lobby Server")
}

/**
 * Helper wrapper function around [GlobalEventHandler.addListener] to support Kotlin reified types
 */
inline fun <reified T : Event> GlobalEventHandler.addListener(noinline event: (T) -> Unit) {
    this.addListener<T>(T::class.java, event)
}