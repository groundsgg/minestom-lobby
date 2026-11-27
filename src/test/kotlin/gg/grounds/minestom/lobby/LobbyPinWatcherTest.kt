package gg.grounds.minestom.lobby

import io.nats.client.Connection
import io.nats.client.Dispatcher
import io.nats.client.Message
import io.nats.client.MessageHandler
import java.lang.reflect.Proxy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LobbyPinWatcherTest {
    @Test
    fun `close releases subscription connection and poller and fences late NATS messages`() {
        val fixture = Resources()
        val watcher = fixture.start()
        assertEquals("maps.pins.stage", fixture.subject)
        fixture.announce()
        val pending = fixture.scheduler.tasks.single()

        watcher.close()
        watcher.close()
        fixture.announce()
        fixture.scheduler.runNext()

        assertTrue(pending.isCancelled)
        assertEquals(1, fixture.scheduler.shutdownCalls)
        assertEquals(1, fixture.dispatcherCloses)
        assertEquals(1, fixture.connectionCloses)
        assertEquals(0, fixture.retired)
        assertTrue(fixture.scheduler.tasks.isEmpty())
    }

    @Test
    fun `connection failure shuts down the allocated executor and preserves the failure`() {
        val fixture = Resources()
        val original = IllegalStateException("connect failed")
        fixture.connectFailure = original

        assertSame(original, assertThrows(IllegalStateException::class.java) { fixture.start() })
        assertTrue(fixture.scheduler.isShutdown)
        assertEquals(0, fixture.connectionCloses)
    }

    @Test
    fun `dispatcher creation failure closes the already connected client`() {
        val fixture = Resources()
        val original = IllegalStateException("dispatcher failed")
        fixture.dispatcherFailure = original

        assertSame(original, assertThrows(IllegalStateException::class.java) { fixture.start() })
        assertTrue(fixture.scheduler.isShutdown)
        assertEquals(1, fixture.connectionCloses)
    }

    @Test
    fun `subscription failure fences delivered messages and releases every allocated resource`() {
        val fixture = Resources()
        val original = IllegalStateException("subscribe failed")
        fixture.subscriptionFailure = original
        fixture.announceOnSubscribe = true

        assertSame(original, assertThrows(IllegalStateException::class.java) { fixture.start() })
        assertTrue(fixture.scheduler.isShutdown)
        assertTrue(fixture.scheduler.tasks.single().isCancelled)
        assertEquals(1, fixture.dispatcherCloses)
        assertEquals(1, fixture.connectionCloses)
        fixture.announce()
        fixture.scheduler.runNext()
        assertEquals(0, fixture.retired)
        assertTrue(fixture.scheduler.tasks.isEmpty())
    }

    @Test
    fun `a cleanup failure cannot skip connection cleanup or replace the startup failure`() {
        val fixture = Resources()
        val original = IllegalStateException("subscribe failed")
        val cleanup = IllegalArgumentException("dispatcher cleanup failed")
        fixture.subscriptionFailure = original
        fixture.dispatcherCloseFailure = cleanup

        val failure = assertThrows(IllegalStateException::class.java) { fixture.start() }

        assertSame(original, failure)
        assertTrue(failure.suppressed.contains(cleanup))
        assertTrue(fixture.scheduler.isShutdown)
        assertEquals(1, fixture.connectionCloses)
    }

    @Test
    fun `close attempts remaining cleanup after a dispatcher failure and is still idempotent`() {
        val fixture = Resources()
        val watcher = fixture.start()
        val cleanup = IllegalArgumentException("dispatcher cleanup failed")
        fixture.dispatcherCloseFailure = cleanup

        assertSame(cleanup, assertThrows(IllegalArgumentException::class.java) { watcher.close() })
        watcher.close()

        assertTrue(fixture.scheduler.isShutdown)
        assertEquals(1, fixture.dispatcherCloses)
        assertEquals(1, fixture.connectionCloses)
    }

    @Test
    fun `interrupted startup retains interruption and still closes allocated resources`() {
        val fixture = Resources()
        val original = InterruptedException("connection interrupted")
        fixture.connectFailure = original
        try {
            assertSame(original, assertThrows(InterruptedException::class.java) { fixture.start() })
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(fixture.scheduler.isShutdown)
            assertEquals(0, fixture.dispatcherCloses)
            assertEquals(0, fixture.connectionCloses)
        } finally {
            Thread.interrupted()
        }
    }

    /** Only the external NATS boundary is faked; the watcher and poller are real. */
    private class Resources {
        val scheduler = ManualScheduler()
        var connectFailure: Throwable? = null
        var dispatcherFailure: Throwable? = null
        var subscriptionFailure: Throwable? = null
        var dispatcherCloseFailure: Throwable? = null
        var announceOnSubscribe = false
        var subject: String? = null
        var dispatcherCloses = 0
        var connectionCloses = 0
        var retired = 0
        lateinit var handler: MessageHandler
        private val dispatcher =
            proxy<Dispatcher> { method, args ->
                when (method) {
                    "subscribe" -> {
                        subject = args!![0] as String
                        if (announceOnSubscribe) announce()
                        subscriptionFailure?.let { throw it }
                        null
                    }
                    else -> error("Unexpected dispatcher call: $method")
                }
            }
        private val connection =
            proxy<Connection> { method, args ->
                when (method) {
                    "createDispatcher" -> {
                        dispatcherFailure?.let { throw it }
                        handler = args!![0] as MessageHandler
                        dispatcher
                    }
                    "closeDispatcher" -> {
                        assertSame(dispatcher, args!![0])
                        // Admission must be closed before any potentially blocking NATS cleanup.
                        assertTrue(scheduler.isShutdown)
                        dispatcherCloses++
                        dispatcherCloseFailure?.let { throw it }
                        null
                    }
                    "close" -> {
                        connectionCloses++
                        null
                    }
                    else -> error("Unexpected connection call: $method")
                }
            }

        fun start() =
            LobbyPinWatcher.start(
                source = LobbyMapSource.Published("lobby/mainlobby", 3, "a".repeat(64)),
                subject = "maps.pins.stage",
                pinnedOnCdn = { "b".repeat(64) },
                retire = { retired++ },
                connect = {
                    connectFailure?.let { throw it }
                    connection
                },
                createScheduler = { scheduler },
            )

        fun announce() {
            val body = """{"maps":{"lobby/mainlobby":{"bundleSha256":"${"b".repeat(64)}"}}}"""
            handler.onMessage(
                proxy<Message> { method, _ ->
                    when (method) {
                        "getData" -> body.encodeToByteArray()
                        else -> error("Unexpected message call: $method")
                    }
                }
            )
        }
    }

    private companion object {
        inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>?) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) {
                _,
                method,
                args ->
                call(method.name, args)
            } as T
    }
}
