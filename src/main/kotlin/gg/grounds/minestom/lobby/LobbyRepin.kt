package gg.grounds.minestom.lobby

import com.google.gson.JsonParser
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

/**
 * Restarts this lobby onto a newly pinned lobby map.
 *
 * service-maps announces every pin file change (see [onPinFile]). When the lobby map's digest is no
 * longer the one this lobby loaded, the lobby [retire]s: proxies move its players to current
 * lobbies and send it no new ones, and once empty it shuts down and the Fleet starts a fresh one.
 *
 * Not before the CDN serves the new pin file, though. The replacement reads the pin from the CDN
 * when it boots, and the CDN may keep the previous file for its `max-age`; a replacement started
 * before then would load the old map again, and no second announcement would ever correct it. So
 * this waits until [pinnedOnCdn] shows the new digest, and gives up waiting after [patience].
 */
internal class LobbyRepin(
    private val loaded: LobbyMapSource.Published,
    private val pinnedOnCdn: () -> String?,
    private val retire: () -> Unit,
    private val scheduler: ScheduledExecutorService,
    private val pollEvery: Duration = Duration.ofSeconds(10),
    private val patience: Duration = Duration.ofMinutes(10),
) {
    private val logger = LoggerFactory.getLogger(LobbyRepin::class.java)

    /** The digest to restart onto, or null while the pin still names the loaded map. */
    @Volatile private var wanted: String? = null
    private val waiting = AtomicBoolean(false)

    /** One announcement: the full pin file, as published to the CDN. */
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
                .getOrNull()
        // Unpinned or unreadable is no instruction: this lobby keeps what it has.
        if (digest == null) return
        wanted = digest.takeIf { it != loaded.sha256 }
        if (wanted == null || !waiting.compareAndSet(false, true)) return
        logger.info(
            "Lobby map re-pinned; retiring once the CDN serves it (map={}, loaded={}, pinned={})",
            loaded.address,
            loaded.sha256,
            digest,
        )
        awaitCdn(System.nanoTime() + patience.toNanos())
    }

    private fun awaitCdn(deadline: Long) {
        scheduler.schedule(
            {
                val target = wanted
                when {
                    // Pinned back to what this lobby already has while it waited.
                    target == null -> waiting.set(false)
                    runCatching(pinnedOnCdn).getOrNull() == target -> retire()
                    System.nanoTime() > deadline -> {
                        logger.warn(
                            "The CDN still serves the previous pin; retiring anyway (map={}, pinned={})",
                            loaded.address,
                            target,
                        )
                        retire()
                    }
                    else -> awaitCdn(deadline)
                }
            },
            pollEvery.toMillis(),
            TimeUnit.MILLISECONDS,
        )
    }
}
