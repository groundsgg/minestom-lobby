package gg.grounds.minestom.lobby

import gg.grounds.minestom.lobby.blockhandler.DisplaySignTextHandler
import gg.grounds.minestom.lobby.commands.LoadWorldCommand
import io.github.oshai.kotlinlogging.KotlinLogging
import net.kyori.adventure.key.Key
import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.GameMode
import net.minestom.server.event.Event
import net.minestom.server.event.GlobalEventHandler
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerBlockBreakEvent
import kotlin.math.log

const val SUNRISE_TIME: Long = 6000

enum class LobbyAuthType {
    NO_PROXY,
    VELOCITY,
}

const val VELOCITY_SECRET_ENV_NAME = "GROUNDS_LOBBY_VELOCITY_SECRET"

object LobbyServer {

    private val logger = KotlinLogging.logger {}

    fun start(address: String, port: Int, isDevMode: Boolean, authType: LobbyAuthType) {
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

        // Instances are Minestom worlds, thus this creates a "world"
        val instanceContainer = MinecraftServer.getInstanceManager().createInstanceContainer()

        // Registers a handler so signs display text. The key minecraft:sign defines that the handler is valid
        // for all minecraft signs.
        // More information about the implementation detail of the handler is inside DisplaySignTextHandler.
        MinecraftServer.getBlockManager().registerHandler(Key.key("minecraft:sign")) { DisplaySignTextHandler }

        val anvilMapManager = AnvilMapManager(instanceContainer)
        anvilMapManager.loadMap(AnvilMapManager.DEFAULT_MAP_NAME).getOrThrow()

        // Disable day-night cycle
        instanceContainer.timeRate = 0
        instanceContainer.time = SUNRISE_TIME

        val globalEventHandler = MinecraftServer.getGlobalEventHandler()

        globalEventHandler.addListener(
            AsyncPlayerConfigurationEvent::class.java
        ) { event: AsyncPlayerConfigurationEvent ->
            val player = event.player

            event.spawningInstance = instanceContainer
            player.gameMode = GameMode.SURVIVAL
        }

        // The server must explicitly forbid to break blocks
        globalEventHandler.addListener<PlayerBlockBreakEvent> { event -> event.isCancelled = true }

        // Apparently, default Minestom has Chat Support.
        // If desired, chat messages can be canceled with the line below
        // globalEventHandler.addListener<PlayerChatEvent> { it.isCancelled = true }
        globalEventHandler.addListener<AsyncPlayerConfigurationEvent> { event ->
            println("${event.player.uuid}/${event.player.username} joined the server")
        }

        if (isDevMode) {
            MinecraftServer.getCommandManager().register(LoadWorldCommand(anvilMapManager))
            logger.info { "### ATTENTION: Server is running in dev mode! ###" }
        }

        minecraftServer.start(address, port)

        when (authType) {
            LobbyAuthType.NO_PROXY ->
                logger.info { "Started Lobby Server as standalone on $address:$port" }
            LobbyAuthType.VELOCITY ->
                logger.info { "Started Lobby Server as behind Velocity Proxy on $address:$port" }
        }
    }
}

/**
 * Helper wrapper function around [GlobalEventHandler.addListener] to support Kotlin reified types
 */
inline fun <reified T : Event> GlobalEventHandler.addListener(noinline event: (T) -> Unit) {
    this.addListener<T>(T::class.java, event)
}
