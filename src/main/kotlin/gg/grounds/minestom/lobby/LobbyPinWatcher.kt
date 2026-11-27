package gg.grounds.minestom.lobby

import io.nats.client.Connection
import io.nats.client.Dispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns one network-wide pin subscription and its CDN poller/executor. Startup transfers all
 * resources to this owner only after subscription succeeds; failures release partial resources.
 *
 * Lifecycle shutdown first fences polling and late dispatcher messages, then releases NATS.
 * Repeated close is inert. It does not wait for CDN reads or retract committed retirement; see
 * [LobbyRepin]. The module lifecycle thread calls close, never the dispatcher callback itself.
 */
internal class LobbyPinWatcher
private constructor(
    private val repin: LobbyRepin,
    private val connection: Connection,
    private val dispatcher: Dispatcher,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cleanup(repin, connection, dispatcher)?.let { throw it }
    }

    companion object {
        /** The factories are narrow owned-resource boundaries; no network work holds poll state. */
        fun start(
            source: LobbyMapSource.Published,
            subject: String,
            pinnedOnCdn: () -> String?,
            retire: () -> Unit,
            connect: () -> Connection,
            createScheduler: () -> ScheduledExecutorService = {
                Executors.newSingleThreadScheduledExecutor { task ->
                    Thread(task, "grounds-lobby-repin").apply { isDaemon = true }
                }
            },
        ): LobbyPinWatcher {
            val repin = LobbyRepin(source, pinnedOnCdn, retire, createScheduler())
            var connection: Connection? = null
            var dispatcher: Dispatcher? = null
            try {
                connection = connect()
                dispatcher =
                    connection.createDispatcher { repin.onPinFile(it.data.decodeToString()) }
                dispatcher.subscribe(subject)
                return LobbyPinWatcher(repin, connection, dispatcher)
            } catch (failure: Throwable) {
                cleanup(repin, connection, dispatcher, failure)
                throw failure
            }
        }

        /** Attempt every release, retaining the startup/first close failure and interruption. */
        private fun cleanup(
            repin: LobbyRepin,
            connection: Connection?,
            dispatcher: Dispatcher?,
            original: Throwable? = null,
        ): Throwable? {
            var failure = original
            var interrupted = original is InterruptedException
            for (release in
                listOf<() -> Unit>(
                    repin::close,
                    { if (dispatcher != null) connection?.closeDispatcher(dispatcher) },
                    { connection?.close() },
                )) {
                try {
                    release()
                } catch (error: Throwable) {
                    if (error is InterruptedException) interrupted = true
                    if (failure == null) failure = error
                    else if (failure !== error) failure.addSuppressed(error)
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
            return failure
        }
    }
}
