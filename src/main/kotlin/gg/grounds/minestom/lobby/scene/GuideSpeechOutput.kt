package gg.grounds.minestom.lobby.scene

import gg.grounds.npc.minestom.presentation.*
import gg.grounds.npc.minestom.spi.NpcPublicationGuard
import kotlin.math.sin

internal enum class GuideSpeechMood {
    GREETING,
    FAREWELL,
}

internal data class GuideVoice(
    val key: String,
    val pitch: Float,
    val volumeScale: Float,
    val durationNanos: Long,
)

/** One speech lease owns only a concrete viewer's optional sound and additive head channel. */
internal class GuideSpeechOutput(
    private val valid: () -> Boolean,
    private val reducedMotion: () -> Boolean,
    private val prepare: (NpcPublicationGuard) -> NpcPresentationStart?,
    private val play: (GuideVoice) -> Unit,
    private val silence: (String) -> Unit,
    private val clock: () -> Long = System::nanoTime,
    private val randomInt: (IntRange) -> Int = { it.random() },
    private val voiceRandomInt: (IntRange) -> Int = { it.random() },
    private val bank: GuideVoiceBank,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var epoch = 0L
    private var preparing: NpcPresentationStart? = null
    private var handle: NpcPresentationHandle? = null
    private var published: NpcPresentationHandle? = null
    private var nextSound: Long? = null
    private var activeKey: String? = null
    private val recentTalk = java.util.ArrayDeque<Int>()
    private var greeted = false
    private var farewelled = false
    private var speaking = false
    private var silentUntil: Long? = null
    private var unavailable = false

    fun reveal(text: String, mood: GuideSpeechMood? = null, remainingNanos: Long = 0) =
        synchronized(lock) {
            if (closed || !valid()) return@synchronized
            if (text.codePoints().noneMatch(Character::isLetterOrDigit)) {
                speaking = false
                optional { handle?.followSharedHeadLook() }
                if (text.codePoints().anyMatch { !Character.isWhitespace(it) }) {
                    val deadline = clock() + randomMillis(PUNCTUATION_GAP_MILLIS)
                    if (silentUntil == null || deadline - silentUntil!! > 0) silentUntil = deadline
                }
                return@synchronized
            }
            speaking = true
            val now = clock()
            if (
                (silentUntil == null || now - silentUntil!! >= 0) &&
                    (nextSound == null || now - nextSound!! >= 0)
            ) {
                val cue =
                    when {
                        mood == GuideSpeechMood.GREETING && !greeted -> mood
                        mood == GuideSpeechMood.FAREWELL && !farewelled -> mood
                        else -> null
                    }
                val voice = selectVoice(cue, remainingNanos)
                if (voice != null) {
                    if (cue == GuideSpeechMood.GREETING) greeted = true
                    if (cue == GuideSpeechMood.FAREWELL) farewelled = true
                    stopVoice()
                    activeKey = voice.key
                    optional { play(voice) }
                    nextSound = now + voice.durationNanos + randomMillis(BREATH_GAP_MILLIS)
                }
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
            nextSound = null
            silentUntil = null
            epoch++
            stopVoice()
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
        internal const val END_SAFETY_NANOS = 100_000_000L
        internal val BREATH_GAP_MILLIS = 275..430
        internal val PUNCTUATION_GAP_MILLIS = 400..700
    }

    private fun selectVoice(mood: GuideSpeechMood?, remainingNanos: Long): GuideVoice? {
        if (remainingNanos < END_SAFETY_NANOS) return null
        val pitch = voiceDraw(965..1035) / 1000f
        val kind =
            when (mood) {
                GuideSpeechMood.GREETING -> "greeting"
                GuideSpeechMood.FAREWELL -> "farewell"
                null -> "speech"
            }
        val candidates =
            bank.clips.withIndex().filter { (index, clip) ->
                clip.kind == kind &&
                    (kind != "speech" || index !in recentTalk) &&
                    clip.playbackNanos(pitch) <= remainingNanos - END_SAFETY_NANOS
            }
        if (candidates.isEmpty()) return null
        val (index, clip) = candidates[voiceDraw(0..candidates.lastIndex)]
        if (kind == "speech") {
            recentTalk.addLast(index)
            if (recentTalk.size > 2) recentTalk.removeFirst()
        }
        return GuideVoice(clip.key, pitch, voiceDraw(900..1000) / 1000f, clip.playbackNanos(pitch))
    }

    private fun voiceDraw(range: IntRange): Int =
        voiceRandomInt(range).also {
            require(it in range) { "Guide voice draw must stay within its requested bounds" }
        }

    private fun stopVoice() {
        activeKey?.let { key -> optional { silence(key) } }
        activeKey = null
    }

    private fun boundedRandom(range: IntRange): Int =
        randomInt(range).also {
            require(it in range) { "Speech rhythm draw must stay within its requested bounds" }
        }

    private fun randomMillis(range: IntRange) = boundedRandom(range) * 1_000_000L

    private inline fun optional(action: () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
            /* Speech is optional; dialogue admission remains valid. */
        }
    }
}
