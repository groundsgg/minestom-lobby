package gg.grounds.minestom.lobby

import gg.grounds.GroundsPluginAgones
import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.event.Event
import net.minestom.server.event.GlobalEventHandler
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.instance.LightingChunk
import net.minestom.server.instance.block.Block
import net.minestom.server.utils.chunk.ChunkSupplier

const val SUNRISE_TIME: Long = 6000

enum class LobbyAuthType {
    NO_PROXY,
    VELOCITY,
}

const val VELOCITY_SECRET_ENV_NAME = "GROUNDS_LOBBY_VELOCITY_SECRET"

object LobbyServer {

    fun start(address: String, port: Int, authType: LobbyAuthType) {
        val auth =
            when (authType) {
                LobbyAuthType.NO_PROXY -> {
                    Auth.Online()
                }
                LobbyAuthType.VELOCITY -> {
                    val secret: String =
                        System.getenv(VELOCITY_SECRET_ENV_NAME)
                            ?: throw IllegalArgumentException(
                                "Env '$VELOCITY_SECRET_ENV_NAME' is not set, but is required for the lobby to work with velocity"
                            )
                    Auth.Velocity(secret)
                }
            }

        val minecraftServer = MinecraftServer.init(auth)
        MinecraftServer.setBrandName("Grounds Lobby")

        val instanceContainer = MinecraftServer.getInstanceManager().createInstanceContainer()

        // Just creates an infinite flat world
        instanceContainer.setGenerator { it.modifier().fillHeight(0, 40, Block.GRASS_BLOCK) }
        instanceContainer.chunkSupplier = ChunkSupplier { instance, x, y ->
            LightingChunk(instance, x, y)
        }

        // Freeze the day-night cycle at sunrise (minestom 26.1.2 clock API:
        // the old Instance.timeRate/time setters moved to Instance.defaultClock()).
        val clock = instanceContainer.defaultClock()!!
        clock.rate(0f)
        clock.time(SUNRISE_TIME)

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

        globalEventHandler.addListener<PlayerDisconnectEvent>() {
            println("${it.player.uuid}/${it.player.username} left the server")
        }

        // Register as an Agones GameServer only when the SDK sidecar is present.
        // The Agones webhook sets AGONES_SDK_HTTP_PORT on GameServer pods, so the
        // lobby calls ready() and Velocity's plugin-agones can discover it. A plain
        // standalone lobby (e.g. local NO_PROXY dev) has no sidecar; calling ready()
        // there just loops on connection errors. Mirrors plugin-agones-paper's gate,
        // which the minestom variant doesn't apply itself.
        if (!System.getenv("AGONES_SDK_HTTP_PORT").isNullOrBlank()) {
            GroundsPluginAgones().enable()
        }

        minecraftServer.start(address, port)

        when (authType) {
            LobbyAuthType.NO_PROXY ->
                println("Started Lobby Server as standalone on $address:$port")
            LobbyAuthType.VELOCITY ->
                println("Started Lobby Server as behind Velocity Proxy on $address:$port")
        }
    }
}

/**
 * Helper wrapper function around [GlobalEventHandler.addListener] to support Kotlin reified types
 */
inline fun <reified T : Event> GlobalEventHandler.addListener(noinline event: (T) -> Unit) {
    this.addListener<T>(T::class.java, event)
}
