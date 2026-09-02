package gg.grounds.minestom.lobby

import gg.grounds.lobby.LobbyNavigatorService
import gg.grounds.lobby.scene.LobbySceneCatalogs
import gg.grounds.minestom.lobby.scene.*
import gg.grounds.modules.ServiceRegistry
import gg.grounds.modules.core.DefaultServiceRegistry
import gg.grounds.modules.register
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.runtime.GroundsServerContext
import gg.grounds.runtime.RuntimeEnvironment
import gg.grounds.runtime.ServerType
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LobbyModuleLifecycleTest {
    @TempDir lateinit var root: Path

    @Test
    fun `runtime startup failure reaches the injected fatal stop callback`() {
        val fixture = scene()
        writeScene(fixture.with(elements = fixture.elements.filterIsInstance<Prop>()))
        val fatal = CountDownLatch(1)
        val creation = CompletableFuture<SceneRuntimeCreationResult>()
        val module =
            LobbyModule(
                fatalStop = { fatal.countDown() },
                createWorld = ::world,
                createRuntime = { creation },
            )
        try {
            module.install(SceneContext())
            module.start()
            creation.completeExceptionally(IllegalStateException("renderer failed"))
            assertTrue(fatal.await(2, TimeUnit.SECONDS))
        } finally {
            module.stop()
        }
    }

    @Test
    fun `cleanup failure prevents a successful shutdown return`() {
        val fixture = scene()
        writeScene(fixture.with(elements = fixture.elements.filterIsInstance<Prop>()))
        val runtime = ControlledRuntime()
        val module =
            LobbyModule(
                fatalStop = {},
                createWorld = ::world,
                createRuntime = {
                    CompletableFuture.completedFuture(SceneRuntimeCreationResult.Success(runtime))
                },
            )
        module.install(SceneContext())
        module.start()
        runtime.closed.completeExceptionally(IllegalStateException("cleanup failed"))
        assertThrows(IllegalStateException::class.java) { module.stop() }
        assertNull(MinecraftServer.getCommandManager().getCommand("spawn"))
    }

    @Test
    fun `bad selected scene fails installation before listeners or commands`() {
        Files.writeString(root.resolve("scene.json"), "invalid")
        val context = SceneContext()
        val module = LobbyModule(fatalStop = {}, createWorld = ::world)
        try {
            assertThrows(IllegalStateException::class.java) { module.install(context) }
            assertNull(MinecraftServer.getCommandManager().getCommand("spawn"))
            assertEquals(0, context.nodes.size)
        } finally {
            module.stop()
        }
    }

    @Test
    fun `selected navigator action requires the installed service before registration`() {
        writeScene()
        val context = SceneContext()
        val module = LobbyModule(fatalStop = {}, createWorld = ::world)
        try {
            val failure =
                assertThrows(IllegalStateException::class.java) { module.install(context) }
            assertTrue(failure.message.orEmpty().contains("navigator", ignoreCase = true))
            assertNull(MinecraftServer.getCommandManager().getCommand("spawn"))
            assertEquals(0, context.nodes.size)
        } finally {
            module.stop()
        }
    }

    @Test
    fun `scene without navigator action does not require its service`() {
        val fixture = scene()
        writeScene(fixture.with(elements = fixture.elements.filterIsInstance<Prop>()))
        var request: SceneRuntimeRequest? = null
        val module =
            LobbyModule(
                fatalStop = {},
                createWorld = ::world,
                createRuntime = {
                    request = it
                    CompletableFuture.completedFuture(
                        SceneRuntimeCreationResult.Success(
                            ControlledRuntime().apply { closed.complete(null) }
                        )
                    )
                },
            )
        try {
            module.install(SceneContext())
            assertNotNull(MinecraftServer.getCommandManager().getCommand("spawn"))
            module.start()
            assertNotNull(request)
        } finally {
            module.stop()
        }
    }

    @Test
    fun `module starts runtime after install and waits cleanup before returning to provider teardown`() {
        writeScene()
        val context = SceneContext()
        val opened = CompletableFuture<Boolean>()
        val requestedPlayers = mutableListOf<Player>()
        context.services.register<LobbyNavigatorService>(
            object : LobbyNavigatorService {
                override fun open(player: Player): CompletableFuture<Boolean> {
                    requestedPlayers += player
                    return opened
                }
            }
        )
        val creation = CompletableFuture<SceneRuntimeCreationResult>()
        var request: SceneRuntimeRequest? = null
        val world = world()
        val module =
            LobbyModule(
                fatalStop = {},
                createWorld = { world },
                createRuntime = {
                    request = it
                    creation
                },
            )
        var stopping: Thread? = null
        val runtimeClosed = CompletableFuture<Void>()
        val closeEntered = CountDownLatch(1)
        val runtime =
            object : SceneRuntime {
                override val identity
                    get() = request!!.identity

                override val isClosed
                    get() = closeEntered.count == 0L

                override fun close(): CompletableFuture<Void> {
                    closeEntered.countDown()
                    return runtimeClosed
                }
            }
        try {
            module.install(context)
            assertNull(request, "Install must not create a runtime before ticks begin")
            module.start()
            assertNotNull(request)
            val startedRequest = request!!
            assertSame(world.instance, startedRequest.instance)
            assertEquals(
                "local:" + root.toAbsolutePath().normalize(),
                startedRequest.identity.mapId,
            )
            assertFalse(
                startedRequest.effects.supports(AssetKey("grounds:editor/marker"), AssetKind.SOUND)
            )
            val player = TestPlayer()
            player.setInstance(world.instance, Pos.ZERO).join()
            assertFalse(startedRequest.playerPolicy.hasPermission(player, "lobby.anything"))
            val action =
                startedRequest.actionRegistry.handlerFor(LobbySceneCatalogs.OPEN_NAVIGATOR)!!
            val actionResult =
                action
                    .execute(
                        SceneActionContext(
                            startedRequest.identity,
                            player,
                            LocalId("navigator"),
                            SceneTrigger.RIGHT_CLICK,
                            SceneHand.MAIN,
                            0,
                            emptyMap(),
                            SceneViewerVisualState(),
                        )
                    )
                    .toCompletableFuture()
            assertEquals(listOf(player), requestedPlayers)
            opened.complete(true)
            assertEquals(SceneActionResult.Success, actionResult.join())

            creation.complete(SceneRuntimeCreationResult.Success(runtime))
            val returned = CompletableFuture<Void>()
            stopping =
                Thread.ofPlatform().start {
                    module.stop()
                    returned.complete(null)
                }
            assertTrue(closeEntered.await(2, TimeUnit.SECONDS))
            assertFalse(returned.isDone, "Provider teardown cannot begin before scene cleanup")
            val joining = AsyncPlayerConfigurationEvent(TestPlayer(), true)
            // Keep a reference to the removed node to exercise an already-dispatched event.
            Thread.startVirtualThread { context.nodes.single().call(joining) }.join()
            assertNull(joining.spawningInstance)
            runtimeClosed.complete(null)
            returned.get(2, TimeUnit.SECONDS)
            assertNull(MinecraftServer.getCommandManager().getCommand("spawn"))
        } finally {
            creation.complete(SceneRuntimeCreationResult.Success(runtime))
            runtimeClosed.complete(null)
            stopping?.join(2000)
            module.stop()
            world.instance.entities.toList().forEach { it.remove() }
            MinecraftServer.getInstanceManager().unregisterInstance(world.instance)
        }
    }

    private fun scene() =
        lobbySceneFixture(GroundsAssetCatalog.catalog, Vec3(10.0, 64.0, 8.0), Vec3(5.0, 64.0, 2.0))

    private fun writeScene(scene: SceneDocument = scene()) {
        Files.write(
            root.resolve("scene.json"),
            (SceneJson.encode(scene) as SceneEncodeResult.Success).bytes,
        )
    }

    private fun world() =
        LobbyMap(
            MinecraftServer.getInstanceManager().createInstanceContainer(),
            Pos.ZERO,
            LoadedLobbyMap(root, LobbyMapSource.Local(root)),
        )

    private class SceneContext : GroundsServerContext {
        override val serverType = ServerType.LOBBY
        override val environment = RuntimeEnvironment.TEST
        override val services = DefaultServiceRegistry()
        val nodes = mutableListOf<EventNode<Event>>()

        override fun eventNode(name: String) = EventNode.all(name).also { nodes += it }

        override fun onShutdown(action: () -> Unit) = Unit
    }

    @Test
    fun `install registers spawn command and stop unregisters it`() {
        val commandManager = MinecraftServer.getCommandManager()
        val module = LobbyModule(fatalStop = {})
        assertNull(commandManager.getCommand("spawn"))

        try {
            module.install(TestContext)

            assertNotNull(commandManager.getCommand("spawn"))

            module.stop()

            assertNull(commandManager.getCommand("spawn"))
        } finally {
            module.stop()
        }
    }

    private object TestContext : GroundsServerContext {
        override val serverType: ServerType = ServerType.LOBBY
        override val environment: RuntimeEnvironment = RuntimeEnvironment.TEST
        override val services: ServiceRegistry
            get() = error("LobbyModule does not use services")

        override fun eventNode(name: String): EventNode<Event> = EventNode.all(name)

        override fun onShutdown(action: () -> Unit) = Unit
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun bootMinestom() {
            MinecraftServer.init()
        }
    }
}
