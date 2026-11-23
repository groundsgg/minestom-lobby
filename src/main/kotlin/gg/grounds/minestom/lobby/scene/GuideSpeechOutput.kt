package gg.grounds.minestom.lobby.scene

import gg.grounds.npc.minestom.presentation.*
import gg.grounds.npc.minestom.spi.NpcPublicationGuard
import kotlin.math.sin

/** One speech lease owns only a concrete viewer's optional sound and additive head channel. */
internal class GuideSpeechOutput(
    private val valid: () -> Boolean,
    private val reducedMotion: () -> Boolean,
    private val prepare: (NpcPublicationGuard) -> NpcPresentationStart?,
    private val play: (Float) -> Unit,
    private val silence: () -> Unit,
    private val clock: () -> Long = System::nanoTime,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var epoch = 0L
    private var preparing: NpcPresentationStart? = null
    private var handle: NpcPresentationHandle? = null
    private var published: NpcPresentationHandle? = null
    private var lastSound: Long? = null
    private var speaking = false
    private var silentUntil: Long? = null
    private var unavailable = false

    fun reveal(text: String) =
        synchronized(lock) {
            if (closed || !valid()) return@synchronized
            if (text.codePoints().noneMatch(Character::isLetterOrDigit)) {
                speaking = false
                optional { handle?.followSharedHeadLook() }
                if (text.codePoints().anyMatch { !Character.isWhitespace(it) })
                    silentUntil = clock() + PUNCTUATION_GAP_NANOS
                return@synchronized
            }
            speaking = true
            val now = clock()
            if (
                (silentUntil == null || now - silentUntil!! >= 0) &&
                    (lastSound == null || now - lastSound!! >= VOICE_CADENCE_NANOS)
            ) {
                lastSound = now
                optional { play((1.0 + .035 * sin(now / 170_000_000.0)).toFloat()) }
            }
            if (reducedMotion()) {
                epoch++
                preparing?.let { optional { it.cancel() } }
                preparing = null
                optional { handle?.followSharedHeadLook() }
                optional { handle?.closeAsync() }
                handle = null
                published = null
                return@synchronized
            }
            if (handle == null && preparing == null && !unavailable) begin()
            move()
        }

    private fun begin() {
        val expected = epoch
        var preparedHandle: NpcPresentationHandle? = null
        val guard = NpcPublicationGuard { operation ->
            synchronized(lock) {
                val activeLease = preparedHandle != null && published === preparedHandle
                if (
                    closed ||
                        !valid() ||
                        reducedMotion() ||
                        (!activeLease && (!speaking || expected != epoch))
                )
                    false
                else {
                    operation.run()
                    true
                }
            }
        }
        optional {
            val start = prepare(guard) ?: return@optional
            preparing = start
            start.ready().whenComplete { result, failure ->
                synchronized(lock) {
                    if (preparing === start) preparing = null
                    val ready = (result as? NpcPresentationResult.Ready)?.handle
                    if (ready == null) {
                        if (
                            expected == epoch &&
                                !closed &&
                                (failure != null || result is NpcPresentationResult.Rejected)
                        )
                            unavailable = true
                    } else if (
                        closed || expected != epoch || !speaking || !valid() || reducedMotion()
                    ) {
                        optional { ready.closeAsync() }
                    } else {
                        preparedHandle = ready
                        handle = ready
                        optional {
                            ready.publish().whenComplete { _, error ->
                                synchronized(lock) {
                                    if (
                                        error != null ||
                                            closed ||
                                            handle !== ready ||
                                            !speaking ||
                                            expected != epoch ||
                                            !valid() ||
                                            reducedMotion()
                                    ) {
                                        if (handle === ready) handle = null
                                        if (published === ready) published = null
                                        optional { ready.closeAsync() }
                                    } else {
                                        published = ready
                                        move()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun move() {
        if (!speaking || closed || !valid() || reducedMotion()) return
        val seconds = clock() / 1_000_000_000.0
        optional { handle?.applyHeadLook(2.0 * sin(seconds * 5.0), 1.2 * sin(seconds * 7.0)) }
    }

    fun stop(terminal: Boolean) =
        synchronized(lock) {
            if (closed) return@synchronized
            speaking = false
            epoch++
            optional { silence() }
            preparing?.let { optional { it.cancel() } }
            preparing = null
            handle?.let { current ->
                optional { current.followSharedHeadLook() }
                if (terminal || published !== current) {
                    optional { current.closeAsync() }
                    handle = null
                    if (published === current) published = null
                }
            }
            if (terminal) {
                handle = null
                closed = true
            }
        }

    fun fallback() =
        synchronized(lock) {
            stop(false)
            optional { handle?.closeAsync() }
            handle = null
            published = null
            unavailable = false
        }

    override fun close() = stop(true)

    companion object {
        internal const val VOICE_CADENCE_NANOS = 260_000_000L
        internal const val PUNCTUATION_GAP_NANOS = 100_000_000L
    }

    private inline fun optional(action: () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
            /* Speech is optional; dialogue admission remains valid. */
        }
    }
}
