package gg.grounds.minestom.lobby

import gg.grounds.minestom.lobby.scene.LobbySceneHost
import gg.grounds.minestom.lobby.scene.LobbySceneLoader
import gg.grounds.minestom.lobby.scene.runtimeRequest
import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsServerContext
import gg.grounds.scene.minestom.SceneRuntimeCreationResult
import gg.grounds.scene.minestom.SceneRuntimeFactory
import gg.grounds.scene.minestom.SceneRuntimeRequest
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import net.minestom.server.MinecraftServer
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.thread.MinestomThread
import org.slf4j.LoggerFactory

internal class LobbyModule(
    private val fatalStop: () -> Unit,
    private val createWorld: () -> LobbyMap = LobbyWorld::createInstance,
    private val createRuntime:
        (SceneRuntimeRequest) -> CompletionStage<SceneRuntimeCreationResult> =
        SceneRuntimeFactory::create,
) : GroundsModule {
    private val logger = LoggerFactory.getLogger(LobbyModule::class.java)
    private var eventNode: EventNode<Event>? = null
    private var spawnCommand: SpawnCommand? = null
    private var sceneHost: LobbySceneHost? = null

    override val id: String = "grounds.lobby"

    override fun install(ctx: GroundsServerContext) {
        val (instanceContainer, spawn, loaded) = createWorld()
        val prepared =
            try {
                LobbySceneLoader().load(loaded)?.runtimeRequest(instanceContainer, ctx.services)
            } catch (failure: Exception) {
                // No lobby command/listener has been registered; do not retain this unused world.
                MinecraftServer.getInstanceManager().unregisterInstance(instanceContainer)
                throw failure
            }
        val host =
            prepared?.let { request -> LobbySceneHost({ createRuntime(request) }, fatalStop) }
        sceneHost = host
        if (prepared != null)
            logger.info(
                "Prepared lobby scene {} (map={}, version={}, assets={}, actions={})",
                prepared.identity.sceneId.value,
                prepared.identity.mapId,
                prepared.identity.mapVersion,
                prepared.assets.version,
                prepared.actions.version,
            )
        val command = SpawnCommand(spawn)
        MinecraftServer.getCommandManager().register(command)
        spawnCommand = command

        val node = ctx.eventNode("grounds-lobby")
        if (host == null) LobbyEvents.register(node, instanceContainer, spawn)
        else LobbyEvents.register(node, instanceContainer, spawn, host.ready) { host.isClosed }
        MinecraftServer.getGlobalEventHandler().addChild(node)
        eventNode = node

        logger.info(
            "Installed lobby module successfully (serverType={}, environment={})",
            ctx.serverType,
            ctx.environment,
        )
    }

    override fun start() {
        // GroundsServer invokes module start only after MinecraftServer.start has enabled ticks.
        sceneHost?.start()
    }

    override fun stop() {
        // Close the admission gate first, even if an event already captured our listener node.
        val cleanup = sceneHost?.closeAsync()?.toCompletableFuture()
        eventNode?.let(MinecraftServer.getGlobalEventHandler()::removeChild)
        eventNode = null
        spawnCommand?.let(MinecraftServer.getCommandManager()::unregister)
        spawnCommand = null
        if (cleanup != null) {
            try {
                check(Thread.currentThread() !is MinestomThread) {
                    "Lobby scene shutdown must run on a non-tick lifecycle thread"
                }
                cleanup.get(30, TimeUnit.SECONDS)
            } catch (failure: Exception) {
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                logger.error(
                    "Lobby scene cleanup failed or exceeded 30 seconds; provider teardown cannot safely continue",
                    failure,
                )
                throw IllegalStateException(
                    "Lobby scene cleanup did not complete successfully",
                    failure,
                )
            }
        }
    }
}
