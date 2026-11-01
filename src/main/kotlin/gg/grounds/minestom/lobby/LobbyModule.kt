package gg.grounds.minestom.lobby

import gg.grounds.GameServerRetirement
import gg.grounds.minestom.lobby.scene.LobbySceneHost
import gg.grounds.minestom.lobby.scene.LobbySceneLoader
import gg.grounds.minestom.lobby.scene.runtimeRequest
import gg.grounds.modules.get
import gg.grounds.permissions.Permissions
import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsServerContext
import gg.grounds.scene.minestom.SceneRuntimeCreationResult
import gg.grounds.scene.minestom.SceneRuntimeFactory
import gg.grounds.scene.minestom.SceneRuntimeRequest
import io.nats.client.Connection
import io.nats.client.Nats
import io.nats.client.Options
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import net.minestom.server.MinecraftServer
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
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
    private var world: LobbyMap? = null
    private var pinEvents: Connection? = null
    private var demoAccess: StageNpcDemoAccess? = null
    private var demoCommand: StageNpcDemoCommand? = null
    private var nativeSolidPoc: gg.grounds.minestom.lobby.scene.NativeSolidPoc? = null

    override val id: String = "grounds.lobby"

    override fun install(ctx: GroundsServerContext) {
        val demoEnabled = stageNpcDemoEnabled()
        val demoPack = if (demoEnabled) NpcDemoPack.configured() else null
        val created = createWorld()
        world = created
        val (instanceContainer, spawn, loaded) = created
        val prepared =
            try {
                LobbySceneLoader()
                    .load(loaded)
                    ?.runtimeRequest(instanceContainer, ctx.services)
                    .also {
                        if (demoEnabled)
                            requireStageNpcDemoMap(
                                loaded.source,
                                it?.scene,
                                requireNotNull(System.getenv("GROUNDS_NPC_DEMO_MAP_SHA256")) {
                                    "NPC demo map digest missing"
                                },
                            )
                    }
                    .also { request ->
                        val started = System.nanoTime()
                        val centres =
                            request
                                ?.scene
                                ?.elements
                                ?.filterIsInstance<gg.grounds.scene.format.Npc>()
                                ?.map { npc ->
                                    npc.transform.position.let {
                                        net.minestom.server.coordinate.Vec(it.x, it.y, it.z)
                                    }
                                }
                                .orEmpty()
                        LobbyTerrain.prepare(created, centres)
                        logger.info(
                            "Prepared lobby visitor terrain in {} ms (chunks={})",
                            (System.nanoTime() - started) / 1_000_000,
                            instanceContainer.chunks.size,
                        )
                    }
            } catch (failure: Exception) {
                // No lobby command/listener has been registered; do not retain this unused world.
                MinecraftServer.getInstanceManager().unregisterInstance(instanceContainer)
                throw failure
            }
        val host =
            prepared?.let { request -> LobbySceneHost({ createRuntime(request) }, fatalStop) }
        if (System.getenv("GROUNDS_NPC_NATIVE_SOLID_POC") == "true") {
            require(demoEnabled) { "Native SOLID probe requires the Stage demo" }
            val npc =
                prepared!!.scene.elements.filterIsInstance<gg.grounds.scene.format.Npc>().single {
                    it.id.value == "demo-solid"
                }
            nativeSolidPoc = gg.grounds.minestom.lobby.scene.NativeSolidPoc(instanceContainer, npc)
        }
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
        val demo =
            if (demoEnabled)
                StageNpcDemoAccess(
                    instanceContainer,
                    prepared
                        ?.scene
                        ?.elements
                        ?.filterIsInstance<gg.grounds.scene.format.Npc>()
                        ?.single { it.id.value == "demo-hands" },
                ) { player ->
                    ctx.services
                        .get<Permissions>()
                        ?.hasPermission(player.uuid, "grounds.scene.edit") == true
                }
            else null
        demoAccess = demo
        val command = SpawnCommand(spawn) { demo?.restore(it) }
        MinecraftServer.getCommandManager().register(command)
        spawnCommand = command

        val node = ctx.eventNode("grounds-lobby")
        if (demo != null) {
            val testCommand = StageNpcDemoCommand(demo)
            MinecraftServer.getCommandManager().register(testCommand)
            demoCommand = testCommand
            demo.register(node)
        }
        if (host == null) LobbyEvents.register(node, instanceContainer, spawn)
        else LobbyEvents.register(node, instanceContainer, spawn, host.ready) { host.isClosed }
        if (demo != null) {
            val pack = requireNotNull(demoPack)
            // Minestom waits for the request's future before completing configuration.
            node.addListener(AsyncPlayerConfigurationEvent::class.java) {
                if (it.spawningInstance === instanceContainer && it.player.isOnline)
                    pack.offer(it.player)
            }
        }
        MinecraftServer.getGlobalEventHandler().addChild(node)
        eventNode = node
        watchPins(loaded, ctx)

        logger.info(
            "Installed lobby module successfully (serverType={}, environment={})",
            ctx.serverType,
            ctx.environment,
        )
    }

    override fun start() {
        // GroundsServer invokes module start only after MinecraftServer.start has enabled ticks.
        sceneHost?.start()
        nativeSolidPoc?.let { probe ->
            sceneHost!!
                .ready
                .thenCompose { probe.start() }
                .whenComplete { _, error ->
                    if (error != null) logger.warn("Native SOLID probe could not start", error)
                    else
                        logger.info(
                            "Native SOLID probe ready (station=demo-solid, cubes={})",
                            probe.cubes.size,
                        )
                }
        }
    }

    /**
     * Retires this lobby when its map is re-pinned; see [LobbyRepin]. Only a published map can be
     * re-pinned: a lobby on the image's baked-in world got there because the map service was
     * unreachable, and retiring it would only start another one that cannot reach it either.
     */
    private fun watchPins(loaded: LoadedLobbyMap, ctx: GroundsServerContext) {
        val source = loaded.source as? LobbyMapSource.Published ?: return
        val natsUrl = System.getenv("NATS_URL")
        if (natsUrl.isNullOrBlank()) {
            logger.info("NATS_URL is unset; this lobby keeps its map until restarted")
            return
        }
        val distribution = MapDistribution()
        val repin =
            LobbyRepin(
                source,
                pinnedOnCdn = { distribution.pinnedDigest(source.address) },
                // Looked up when needed: the Agones module may install after this one.
                retire = {
                    ctx.services.get<GameServerRetirement>()?.retire()
                        ?: logger.warn("Lobby map re-pinned, but nothing here can retire it")
                },
                scheduler =
                    Executors.newSingleThreadScheduledExecutor { task ->
                        Thread(task, "grounds-lobby-repin").apply { isDaemon = true }
                    },
            )
        val connection =
            Nats.connectReconnectOnConnect(
                Options.Builder().server(natsUrl).connectionName("lobby-pins").build()
            )
        connection
            .createDispatcher { repin.onPinFile(it.data.decodeToString()) }
            .subscribe("maps.pins.${distribution.environment}")
        pinEvents = connection
    }

    override fun stop() {
        nativeSolidPoc?.close()
        nativeSolidPoc = null
        demoCommand?.let(MinecraftServer.getCommandManager()::unregister)
        demoCommand = null
        demoAccess?.close()
        demoAccess = null
        pinEvents?.close()
        pinEvents = null
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
