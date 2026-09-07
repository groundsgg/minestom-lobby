package gg.grounds.minestom.lobby.scene

import gg.grounds.lobby.scene.LobbySceneCatalogs
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.event.instance.AddEntityToInstanceEvent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class LobbySceneHostTest {
    /**
     * Catches a host completion that abandons a late automatic renderer delivery for a stopped
     * owner scheduler, leaving its display registered after provider teardown begins.
     */
    @Test
    fun `host cleanup awaits a gated automatic display attachment before completing`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val gate = ChunkGate()
        var display: HighlightableBlockDisplay? = null
        instance.eventNode().addListener(AddEntityToInstanceEvent::class.java) {
            if (it.entity is HighlightableBlockDisplay)
                display = it.entity as HighlightableBlockDisplay
        }
        try {
            instance.viewDistance(0)
            TestPlayer().setInstance(instance, Pos.ZERO).join()
            instance.unloadChunk(3, 0)
            gate.install(instance, 3)
            val authored =
                lobbySceneFixture(GroundsAssetCatalog.catalog, ORIGIN, Vec3(48.0, 0.0, 0.0))
            val marker =
                authored.elements
                    .filterIsInstance<Prop>()
                    .single()
                    .copy(activation = ActivationPolicy.AUTOMATIC)
            val scene = authored.with(elements = listOf(marker))
            val policy = LobbyScenePlayerPolicy(instance, null)
            val request =
                SceneRuntimeRequest(
                    scene,
                    GroundsAssetCatalog.catalog,
                    LobbySceneCatalogs.CURRENT,
                    SceneRuntimeIdentity(scene.id, "lobby/test", 1),
                    instance,
                    LobbyPlaceholderRenderers(GroundsAssetCatalog.catalog),
                    UnsupportedLobbySceneEffects,
                    policy,
                    LobbySceneActions(null, policy),
                )
            val host =
                LobbySceneHost({ SceneRuntimeFactory.create(request) }) {
                    fail("Unexpected fatal stop")
                }

            host.start()
            repeat(20) {
                if (host.ready.isDone) return@repeat
                instance.tick(0)
            }
            host.ready.get(5, TimeUnit.SECONDS)
            repeat(20) {
                if (gate.entered.count == 0L) return@repeat
                instance.tick(0)
            }
            gate.entered.awaitReady()
            val attached = checkNotNull(display)

            val closed = host.closeAsync().toCompletableFuture()
            val cleanupAtClose =
                closed.thenRun {
                    assertTrue(attached.isRemoved, "Host close completed before display removal")
                    assertNull(
                        instance.getEntityById(attached.entityId),
                        "Host close completed before display unregistration",
                    )
                }
            instance.tick(0)
            assertFalse(
                closed.isDone,
                "Host cleanup must retain ownership until the attachment settles",
            )

            gate.close()
            gate.settled.awaitReady()
            val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!closed.isDone && System.nanoTime() < cleanupDeadline) instance.tick(0)
            closed.get(5, TimeUnit.SECONDS)
            cleanupAtClose.get(5, TimeUnit.SECONDS)
            assertTrue(attached.isRemoved)
            assertNull(instance.getEntityById(attached.entityId))
            val entitiesAfterCleanup = instance.entities.map { it.entityId }.toSet()
            instance.scheduler().processTick()
            assertEquals(entitiesAfterCleanup, instance.entities.map { it.entityId }.toSet())
        } finally {
            gate.close()
            instance.tick(0)
            instance.entities.toList().forEach { it.remove() }
            MinecraftServer.getInstanceManager().unregisterInstance(instance)
        }
    }

    @Test
    fun `readiness completion can reenter close without losing runtime ownership`() {
        val creation = CompletableFuture<SceneRuntimeCreationResult>()
        val runtime = ControlledRuntime()
        val host = LobbySceneHost({ creation }) { fail("Unexpected fatal stop") }
        val observed =
            host.ready.thenRun {
                assertFalse(host.isClosed)
                assertFalse(host.closeAsync().toCompletableFuture().isDone)
            }
        host.start()
        creation.complete(SceneRuntimeCreationResult.Success(runtime))
        observed.get(2, TimeUnit.SECONDS)
        assertTrue(host.isClosed)
        assertEquals(1, runtime.closeCalls)
        runtime.closed.complete(null)
        host.closeAsync().toCompletableFuture().get(2, TimeUnit.SECONDS)
    }

    @Test
    fun `shutdown publication is observable before pending-ready failure callbacks run`() {
        val host = LobbySceneHost({ CompletableFuture<SceneRuntimeCreationResult>() }) {}
        val observed =
            host.ready.handle { _, failure ->
                assertNotNull(failure)
                assertTrue(host.isClosed)
                assertFalse(host.closeAsync().toCompletableFuture().isDone)
            }
        host.closeAsync().toCompletableFuture().get(2, TimeUnit.SECONDS)
        observed.get(2, TimeUnit.SECONDS)
    }

    @Test
    fun `exceptional creation after shutdown settles close without requesting a second stop`() {
        val creation = CompletableFuture<SceneRuntimeCreationResult>()
        val host = LobbySceneHost({ creation }) { fail("Shutdown already owns server stop") }
        host.start()
        val stopped = host.closeAsync().toCompletableFuture()
        assertFalse(stopped.isDone)
        creation.completeExceptionally(IllegalStateException("attachment failed after close"))
        stopped.get(2, TimeUnit.SECONDS)
        assertTrue(host.ready.isCompletedExceptionally)
    }

    @Test
    fun `synchronous runtime close failure remains observable`() {
        val runtime =
            object : SceneRuntime {
                override val identity =
                    SceneRuntimeIdentity(SceneId("grounds:lobby-stage"), "lobby/mainlobby", 7)
                override val isClosed = false

                override fun close(): CompletableFuture<Void> =
                    throw IllegalStateException("close failed")
            }
        val host =
            LobbySceneHost({
                CompletableFuture.completedFuture(SceneRuntimeCreationResult.Success(runtime))
            }) {}
        host.start()
        assertTrue(host.closeAsync().toCompletableFuture().isCompletedExceptionally)
    }

    @Test
    fun `stop during creation awaits the late runtime cleanup and denies admission`() {
        val creation = CompletableFuture<SceneRuntimeCreationResult>()
        val runtime = ControlledRuntime()
        val host = LobbySceneHost({ creation }) { fail("Unexpected fatal stop") }
        host.start()
        val stopped = host.closeAsync().toCompletableFuture()
        creation.complete(SceneRuntimeCreationResult.Success(runtime))
        assertFalse(stopped.isDone)
        assertTrue(host.isClosed)
        runtime.closed.complete(null)
        stopped.get(2, TimeUnit.SECONDS)
        assertTrue(host.ready.isCompletedExceptionally)
        assertEquals(1, runtime.closeCalls)
        assertSame(stopped, host.closeAsync())
    }

    @Test
    fun `ready stays pending until creation and ordinary repeated close shares cleanup`() {
        val creation = CompletableFuture<SceneRuntimeCreationResult>()
        val runtime = ControlledRuntime()
        var creations = 0
        val host =
            LobbySceneHost({
                creations++
                creation
            }) {
                fail("Unexpected fatal stop")
            }
        host.start()
        host.start()
        assertEquals(1, creations)
        assertFalse(host.ready.isDone)
        creation.complete(SceneRuntimeCreationResult.Success(runtime))
        host.ready.get(2, TimeUnit.SECONDS)
        assertFalse(host.isClosed)
        val stopped = host.closeAsync().toCompletableFuture()
        assertTrue(host.isClosed)
        assertFalse(stopped.isDone)
        assertSame(stopped, host.closeAsync())
        assertEquals(1, runtime.closeCalls)
        runtime.closed.complete(null)
        stopped.get(2, TimeUnit.SECONDS)
    }

    @Test
    fun `close before start never creates and fails readiness`() {
        val host = LobbySceneHost({ fail("Must not create after close") }) {}
        host.closeAsync().toCompletableFuture().get(2, TimeUnit.SECONDS)
        host.start()
        assertTrue(host.ready.isCompletedExceptionally)
        assertTrue(host.isClosed)
    }

    @Test
    fun `creation failure requests fatal stop on a separate lifecycle thread`() {
        val creation = CompletableFuture<SceneRuntimeCreationResult>()
        val stopped = CountDownLatch(1)
        val caller = Thread.currentThread()
        var stopThread: Thread? = null
        val host =
            LobbySceneHost({ creation }) {
                stopThread = Thread.currentThread()
                stopped.countDown()
            }
        host.start()
        creation.complete(
            SceneRuntimeCreationResult.Failure(
                listOf(
                    SceneRuntimeProblem(
                        SceneRuntimeProblemCode.ACTIVATION_FAILED,
                        "elements/navigator",
                        null,
                        "Attachment failed",
                    )
                )
            )
        )
        assertTrue(stopped.await(2, TimeUnit.SECONDS))
        assertNotSame(caller, stopThread)
        assertTrue(host.ready.isCompletedExceptionally)
        assertTrue(host.isClosed)
        host.closeAsync().toCompletableFuture().get(2, TimeUnit.SECONDS)
    }

    @Test
    fun `synchronous creation exception fails ready and requests stop`() {
        val stopped = CountDownLatch(1)
        val host =
            LobbySceneHost({ throw IllegalStateException("create failed") }) { stopped.countDown() }
        host.start()
        assertTrue(stopped.await(2, TimeUnit.SECONDS))
        assertTrue(host.ready.isCompletedExceptionally)
        assertTrue(host.isClosed)
    }

    @Test
    fun `close failure is observable and not reported as successful cleanup`() {
        val runtime = ControlledRuntime()
        val host =
            LobbySceneHost({
                CompletableFuture.completedFuture(SceneRuntimeCreationResult.Success(runtime))
            }) {}
        host.start()
        val stopped = host.closeAsync().toCompletableFuture()
        runtime.closed.completeExceptionally(IllegalStateException("cleanup failed"))
        assertTrue(stopped.isCompletedExceptionally)
        assertTrue(host.isClosed)
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun bootMinestom() {
            MinecraftServer.init()
        }
    }
}

internal class ControlledRuntime : SceneRuntime {
    override val identity =
        SceneRuntimeIdentity(SceneId("grounds:lobby-stage"), "lobby/mainlobby", 7)
    override val isClosed
        get() = closeCalls > 0

    val closed = CompletableFuture<Void>()
    var closeCalls = 0

    override fun close(): CompletableFuture<Void> {
        closeCalls++
        return closed
    }
}
