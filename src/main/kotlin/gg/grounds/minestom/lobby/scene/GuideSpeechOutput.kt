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
    private var activeEnd: Long? = null
    private val recentTalk = java.util.ArrayDeque<Int>()
    private var greeted = false
    private var farewelled = false
    private var speaking = false
    private var deadline: Long? = null
    private var phaseMood: GuideSpeechMood? = null
    private var frameNanos = 35_000_000L
    private var lastCallback: Long? = null
    private var completedPlan = false
    private val planner = GuideSpeechPlanner(bank)
    private var unavailable = false

    fun beginSpeech(endNanos: Long, graphemeDelayNanos: Long, mood: GuideSpeechMood? = null) =
        synchronized(lock) {
            if (closed || !valid()) return@synchronized
            stop(false)
            deadline = endNanos
            frameNanos = maxOf(graphemeDelayNanos, 50_000_000L)
            phaseMood = mood
            completedPlan = false
        }

    fun reveal(text: String) =
        synchronized(lock) {
            if (closed || !valid()) return@synchronized
            val end = deadline ?: return@synchronized
            val now = clock()
            if (end - now <= 0) return@synchronized
            lastCallback?.let { previous ->
                val elapsed = now - previous
                if (elapsed > 250_000_000L) completedPlan = true
                if (elapsed in 1_000_000L..100_000_000L) frameNanos = elapsed
            }
            lastCallback = now
            speaking = text.codePoints().anyMatch(Character::isLetterOrDigit)
            if (!completedPlan && (nextSound == null || now - nextSound!! >= 0)) {
                val cue =
                    when {
                        phaseMood == GuideSpeechMood.GREETING && !greeted -> phaseMood
                        phaseMood == GuideSpeechMood.FAREWELL && !farewelled -> phaseMood
                        else -> null
                    }
                var plan = planner.plan(now, end, frameNanos, recentTalk.toList(), cue)
                var choice = frameNanos
                val latest = activeEnd?.let { it - now + 100_000_000L }
                while (latest != null && choice <= latest) {
                    val alternative =
                        planner.plan(now + choice, end, frameNanos, recentTalk.toList(), cue)
                    fun finish(candidate: List<PlannedGuideVoice>) =
                        candidate.lastOrNull()?.let { it.onsetNanos - now + it.voice.durationNanos }
                            ?: 0L
                    if (finish(alternative) > finish(plan) + 1_000L) plan = alternative
                    choice += frameNanos
                }
                if (plan.firstOrNull()?.onsetNanos?.let { it - now > 0 } == true) {
                    nextSound = plan.first().onsetNanos
                    return@synchronized
                }
                val voice = plan.firstOrNull()?.voice
                if (voice == null) completedPlan = true
                if (
                    voice != null &&
                        voice.durationNanos <= end - now - GuideSpeechPlanner.END_GUARD_NANOS
                ) {
                    if (cue == GuideSpeechMood.GREETING) greeted = true
                    if (cue == GuideSpeechMood.FAREWELL) farewelled = true
                    val index = bank.clips.indexOfFirst { it.key == voice.key }
                    if (bank.clips[index].kind == "speech") {
                        recentTalk.addLast(index)
                        if (recentTalk.size > 2) recentTalk.removeFirst()
                    }
                    stopVoice()
                    activeKey = voice.key
                    optional { play(voice.copy(volumeScale = voiceDraw(900..1000) / 1000f)) }
                    activeEnd = now + voice.durationNanos
                    nextSound = activeEnd!! + 40_000_000L
                    completedPlan = plan.size == 1
                }
            }
            if (!speaking) {
                optional { handle?.followSharedHeadLook() }
                return@synchronized
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
            activeEnd = null
            deadline = null
            phaseMood = null
            lastCallback = null
            completedPlan = false
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

    private fun voiceDraw(range: IntRange): Int =
        voiceRandomInt(range).also {
            require(it in range) { "Guide voice draw must stay within its requested bounds" }
        }

    private fun stopVoice() {
        activeKey?.let { key -> optional { silence(key) } }
        activeKey = null
    }

    private inline fun optional(action: () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
            /* Speech is optional; dialogue admission remains valid. */
        }
    }
}
