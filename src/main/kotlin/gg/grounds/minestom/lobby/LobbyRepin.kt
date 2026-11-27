package gg.grounds.minestom.lobby

import com.google.gson.JsonParser
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Restarts this lobby onto a newly pinned published map once the CDN serves it, or after
 * [patience]. Proxies move its players to current lobbies and send it no new ones; the Fleet
 * replaces it when empty. Waiting for the CDN avoids a replacement loading the same cached map
 * again.
 *
 * Owns [scheduler] and its pending poll. NATS announcements, polls and [close] serialize state on
 * [state]; foreign CDN reads and retirement callbacks always run outside that monitor. A read that
 * finishes after close cannot dispatch retirement or rearm polling. A changed target is polled
 * again before retirement is committed.
 *
 * Close is nonblocking with respect to foreign callbacks: it cancels pending work and interrupts
 * the executor, but cannot retract retirement already committed under [state]. Such a callback may
 * finish (or begin executing) after close returns.
 */
internal class LobbyRepin(
    private val loaded: LobbyMapSource.Published,
    private val pinnedOnCdn: () -> String?,
    private val retire: () -> Unit,
    private val scheduler: ScheduledExecutorService,
    private val pollEvery: Duration = Duration.ofSeconds(10),
    private val patience: Duration = Duration.ofMinutes(10),
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(LobbyRepin::class.java)
    private val state = Any()
    // All lifecycle fields are guarded by state, including the scheduling/retirement decision.
    private var closed = false
    private var retiring = false
    private var wanted: String? = null
    private var waiting = false
    private var pending: ScheduledFuture<*>? = null

    /** One network-wide announcement: the full pin file, as published to the CDN. */
    fun onPinFile(body: String) {
        val digest =
            runCatching {
                    JsonParser.parseString(body)
                        .asJsonObject
                        .getAsJsonObject("maps")
                        ?.getAsJsonObject(loaded.address)
                        ?.get("bundleSha256")
                        ?.asString
                }
                .getOrNull() ?: return
        synchronized(state) {
            if (closed || retiring) return
            // Unpinned or unreadable is no instruction; pinning back cancels retirement.
            wanted = digest.takeIf { it != loaded.sha256 }
            if (wanted == null || waiting) return
            waiting = true
            awaitCdn(System.nanoTime() + patience.toNanos())
        }
        logger.info(
            "Scheduled lobby map retirement check successfully (map={}, loaded={}, pinned={})",
            loaded.address,
            loaded.sha256,
            digest,
        )
    }

    /** Called only under [state], so close cannot race a new scheduled future into existence. */
    private fun awaitCdn(deadline: Long) {
        pending =
            scheduler.schedule({ poll(deadline) }, pollEvery.toMillis(), TimeUnit.MILLISECONDS)
    }

    private fun poll(deadline: Long) {
        val target =
            synchronized(state) {
                if (closed || retiring) return
                wanted
                    ?: run {
                        waiting = false
                        pending = null
                        return
                    }
            }
        // Failed reads still reach the lifecycle/deadline decision; interruption is retained.
        val served =
            try {
                pinnedOnCdn()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            } catch (_: Exception) {
                null
            }
        val expired =
            synchronized(state) {
                if (closed || retiring) return
                if (wanted == null) {
                    waiting = false
                    pending = null
                    return
                }
                if (wanted != target) {
                    awaitCdn(deadline)
                    return
                }
                val timedOut = System.nanoTime() > deadline
                if (served != target && !timedOut) {
                    awaitCdn(deadline)
                    return
                }
                retiring = true
                pending = null
                timedOut && served != target
            }
        if (expired)
            logger.warn("Lobby map CDN wait expired (map={}, pinned={})", loaded.address, target)
        retire()
    }

    override fun close() {
        val task =
            synchronized(state) {
                if (closed) return
                closed = true
                pending.also { pending = null }
            }
        try {
            task?.cancel(true)
        } finally {
            scheduler.shutdownNow()
        }
    }
}
