package gg.grounds.minestom.lobby

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LobbyRepinTest {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
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

        assertTrue(retired.await(2, TimeUnit.SECONDS))
        assertEquals(2, cdnReads.get())
    }

    @Test
    fun `the digest this lobby loaded changes nothing`() {
        val retired = CountDownLatch(1)
        repin(pinnedOnCdn = { OLD }, retire = retired::countDown).onPinFile(pinFile(OLD))

        assertFalse(retired.await(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a pin moved back while waiting cancels the restart`() {
        val retired = CountDownLatch(1)
        val repin = repin(pinnedOnCdn = { OLD }, retire = retired::countDown)

        repin.onPinFile(pinFile(NEW))
        repin.onPinFile(pinFile(OLD))

        assertFalse(retired.await(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a CDN that never catches up still retires after the patience runs out`() {
        val retired = CountDownLatch(1)
        repin(pinnedOnCdn = { OLD }, retire = retired::countDown, patience = Duration.ofMillis(50))
            .onPinFile(pinFile(NEW))

        assertTrue(retired.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `another map's pin is not this lobby's business`() {
        val retired = CountDownLatch(1)
        repin(pinnedOnCdn = { NEW }, retire = retired::countDown)
            .onPinFile("""{"maps":{"bedwars/crater":{"bundleSha256":"$NEW"}}}""")

        assertFalse(retired.await(200, TimeUnit.MILLISECONDS))
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
