package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.minestom.SceneRuntime
import gg.grounds.scene.minestom.SceneRuntimeCreationResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.slf4j.LoggerFactory

internal class LobbySceneHost(
    private val create: () -> CompletionStage<SceneRuntimeCreationResult>,
    private val fatalStop: () -> Unit,
) {
    private enum class State {
        NEW,
        STARTING,
        RUNNING,
        CLOSED,
    }

    private val lock = Any()
    private var state = State.NEW
    private var runtime: SceneRuntime? = null
    private val closed = CompletableFuture<Void>()
    val ready = CompletableFuture<Void>()
    val isClosed: Boolean
        get() = synchronized(lock) { state == State.CLOSED }

    // Called by GroundsModule.start, after Minestom starts ticking. Never await here.
    fun start() {
        synchronized(lock) {
            if (state != State.NEW) return
            state = State.STARTING
        }
        try {
            create().whenComplete { result, failure ->
                when {
                    failure != null -> creationFailed(failure)
                    result is SceneRuntimeCreationResult.Success -> created(result.runtime)
                    result is SceneRuntimeCreationResult.Failure ->
                        creationFailed(
                            IllegalStateException("Lobby scene creation failed: ${result.problems}")
                        )
                    else ->
                        creationFailed(IllegalStateException("Scene creation returned no result"))
                }
            }
        } catch (failure: Throwable) {
            creationFailed(failure)
        }
    }

    private fun created(created: SceneRuntime) {
        val mustClose =
            synchronized(lock) {
                if (state == State.CLOSED) true
                else {
                    runtime = created
                    state = State.RUNNING
                    // Publish readiness under the same monitor as CLOSED. Otherwise shutdown can
                    // win the state transition but lose completion of a still-pending ready future.
                    // Admission consumes this future only via get; it installs no blocking
                    // callbacks.
                    ready.complete(null)
                    false
                }
            }
        if (mustClose) closeRuntime(created)
    }

    private fun creationFailed(failure: Throwable) {
        val requestStop =
            synchronized(lock) {
                val wasClosed = state == State.CLOSED
                state = State.CLOSED
                ready.completeExceptionally(failure)
                !wasClosed
            }
        closed.complete(null)
        if (requestStop) {
            LoggerFactory.getLogger(LobbySceneHost::class.java)
                .error("Lobby scene startup failed", failure)
            // GroundsServer.stop awaits instance-owned cleanup: never run it from a tick callback.
            Thread.ofPlatform().name("lobby-scene-fatal-stop").start(fatalStop)
        }
    }

    fun closeAsync(): CompletionStage<Void> {
        val previous: State
        val owned: SceneRuntime?
        synchronized(lock) {
            previous = state
            if (previous == State.CLOSED) return closed
            state = State.CLOSED
            owned = runtime
            runtime = null
            ready.completeExceptionally(IllegalStateException("Lobby scene host is closed"))
        }
        when (previous) {
            State.NEW -> closed.complete(null)
            State.RUNNING -> closeRuntime(checkNotNull(owned))
            State.STARTING -> Unit // The creation callback owns late-runtime cleanup.
            State.CLOSED -> Unit
        }
        return closed
    }

    private fun closeRuntime(owned: SceneRuntime) {
        try {
            owned.close().whenComplete { _, failure ->
                if (failure == null) closed.complete(null)
                else closed.completeExceptionally(failure)
            }
        } catch (failure: Throwable) {
            closed.completeExceptionally(failure)
        }
    }
}
