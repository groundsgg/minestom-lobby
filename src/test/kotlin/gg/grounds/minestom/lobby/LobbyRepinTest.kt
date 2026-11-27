package gg.grounds.minestom.lobby

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Delayed
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LobbyRepinTest {

    private val scheduler = ManualScheduler()
    private val loaded = LobbyMapSource.Published("lobby/mainlobby", 3, OLD)

    @AfterEach
    fun shutdown() {
        scheduler.shutdownNow()
    }

    @Test
    fun `a new digest retires the lobby once the CDN serves it`() {
        val cdnReads = AtomicInteger()
        val retired = CountDownLatch(1)
        val repin =
            repin(
                // The first read still sees the previous file, as a CDN within its max-age would.
                pinnedOnCdn = { if (cdnReads.incrementAndGet() < 2) OLD else NEW },
                retire = retired::countDown,
            )

        repin.onPinFile(pinFile(NEW))

        scheduler.runNext()
        assertEquals(1L, retired.count)
        scheduler.runNext()
        assertEquals(0L, retired.count)
        assertEquals(2, cdnReads.get())
    }

    @Test
    fun `the digest this lobby loaded changes nothing`() {
        val retired = CountDownLatch(1)
        repin(pinnedOnCdn = { OLD }, retire = retired::countDown).onPinFile(pinFile(OLD))

        assertEquals(1L, retired.count)
        assertTrue(scheduler.tasks.isEmpty())
    }

    @Test
    fun `a pin moved back while waiting cancels the restart`() {
        val retired = CountDownLatch(1)
        val repin = repin(pinnedOnCdn = { OLD }, retire = retired::countDown)

        repin.onPinFile(pinFile(NEW))
        repin.onPinFile(pinFile(OLD))

        scheduler.runNext()
        assertEquals(1L, retired.count)
        assertTrue(scheduler.tasks.isEmpty())
    }

    @Test
    fun `a CDN that never catches up still retires after the patience runs out`() {
        val retired = CountDownLatch(1)
        repin(pinnedOnCdn = { OLD }, retire = retired::countDown, patience = Duration.ofNanos(-1))
            .onPinFile(pinFile(NEW))

        scheduler.runNext()
        assertEquals(0L, retired.count)
    }

    @Test
    fun `another map's pin is not this lobby's business`() {
        val retired = CountDownLatch(1)
        repin(pinnedOnCdn = { NEW }, retire = retired::countDown)
            .onPinFile("""{"maps":{"bedwars/crater":{"bundleSha256":"$NEW"}}}""")

        assertEquals(1L, retired.count)
        assertTrue(scheduler.tasks.isEmpty())
    }

    @Test
    fun `close cancels a pending poll and shuts down the owned executor once`() {
        var retired = 0
        val repin = repin(pinnedOnCdn = { NEW }, retire = { retired++ })
        repin.onPinFile(pinFile(NEW))
        val pending = scheduler.tasks.single()
        assertEquals(10L, pending.delayMillis)

        repin.close()
        repin.close()

        assertTrue(pending.isCancelled)
        assertTrue(scheduler.isShutdown)
        assertEquals(1, scheduler.shutdownCalls)
        // An already-dispatched callback may still run after its future was cancelled.
        scheduler.runNext()
        assertEquals(0, retired)
    }

    @Test
    fun `a late announcement after close performs no work`() {
        var reads = 0
        var retired = 0
        val repin =
            repin(
                pinnedOnCdn = {
                    reads++
                    NEW
                },
                retire = { retired++ },
            )
        repin.close()

        repin.onPinFile(pinFile(NEW))

        assertTrue(scheduler.tasks.isEmpty())
        assertEquals(0, reads)
        assertEquals(0, retired)
    }

    @Test
    fun `a CDN read returning after close cannot retire or rearm`() {
        for (result in listOf(OLD, NEW)) {
            val scheduler = ManualScheduler()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            var retired = 0
            val repin =
                LobbyRepin(
                    loaded,
                    pinnedOnCdn = {
                        entered.countDown()
                        assertTrue(release.await(2, TimeUnit.SECONDS))
                        result
                    },
                    retire = { retired++ },
                    scheduler = scheduler,
                )
            repin.onPinFile(pinFile(NEW))
            val polling =
                Thread.ofPlatform().start {
                    try {
                        scheduler.runNext()
                    } finally {
                        finished.countDown()
                    }
                }
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                // This must return while foreign CDN code is still blocked.
                repin.close()
                assertEquals(1L, finished.count)
            } finally {
                release.countDown()
                polling.join(2000)
            }
            assertEquals(0L, finished.count)
            assertEquals(0, retired)
            assertTrue(scheduler.tasks.isEmpty())
        }
    }

    @Test
    fun `a pin moved back during a CDN read fences the stale result`() {
        var retired = 0
        lateinit var repin: LobbyRepin
        repin =
            repin(
                pinnedOnCdn = {
                    repin.onPinFile(pinFile(OLD))
                    NEW
                },
                retire = { retired++ },
            )
        repin.onPinFile(pinFile(NEW))

        scheduler.runNext()

        assertEquals(0, retired)
        assertTrue(scheduler.tasks.isEmpty())
    }

    @Test
    fun `a newer pin during a CDN read is polled before retirement`() {
        val newest = "c".repeat(64)
        var retired = 0
        var reads = 0
        lateinit var repin: LobbyRepin
        repin =
            repin(
                pinnedOnCdn = {
                    if (reads++ == 0) {
                        repin.onPinFile(pinFile(newest))
                        NEW
                    } else newest
                },
                retire = { retired++ },
            )
        repin.onPinFile(pinFile(NEW))

        scheduler.runNext()
        assertEquals(0, retired)
        scheduler.runNext()
        assertEquals(1, retired)
    }

    @Test
    fun `close does not wait for or retract a retirement already dispatched`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val repin =
            repin(
                pinnedOnCdn = { NEW },
                retire = {
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                    finished.countDown()
                },
            )
        repin.onPinFile(pinFile(NEW))
        val polling = Thread.ofPlatform().start { scheduler.runNext() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repin.close()
            assertEquals(1L, finished.count)
        } finally {
            release.countDown()
            polling.join(2000)
        }
        assertEquals(0L, finished.count)
        assertTrue(scheduler.isShutdown)
        assertTrue(scheduler.tasks.isEmpty())
    }

    private fun repin(
        pinnedOnCdn: () -> String?,
        retire: () -> Unit,
        patience: Duration = Duration.ofSeconds(5),
    ) =
        LobbyRepin(
            loaded,
            pinnedOnCdn,
            retire,
            scheduler,
            pollEvery = Duration.ofMillis(10),
            patience = patience,
        )

    private fun pinFile(digest: String) =
        """{"environment":"stage","maps":{"lobby/mainlobby":{"version":4,"bundleSha256":"$digest"}}}"""

    private companion object {
        val OLD = "a".repeat(64)
        val NEW = "b".repeat(64)
    }
}

/** Scheduling is manual; dispatch can reproduce a callback already handed to an executor. */
internal class ManualScheduler : ScheduledThreadPoolExecutor(1) {
    val tasks = ArrayDeque<Task>()
    var shutdownCalls = 0

    override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
        Task(command, unit.toMillis(delay)).also { tasks.addLast(it) }

    fun runNext() = tasks.removeFirst().command.run()

    override fun shutdownNow(): MutableList<Runnable> {
        shutdownCalls++
        return super.shutdownNow()
    }

    class Task(val command: Runnable, val delayMillis: Long) :
        FutureTask<Unit>(command, Unit), ScheduledFuture<Unit> {
        override fun getDelay(unit: TimeUnit) = unit.convert(delayMillis, TimeUnit.MILLISECONDS)

        override fun compareTo(other: Delayed) =
            getDelay(TimeUnit.MILLISECONDS).compareTo(other.getDelay(TimeUnit.MILLISECONDS))
    }
}
