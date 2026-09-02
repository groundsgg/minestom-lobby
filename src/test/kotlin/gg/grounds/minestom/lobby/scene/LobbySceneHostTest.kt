package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.SceneId
import gg.grounds.scene.minestom.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LobbySceneHostTest {
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
