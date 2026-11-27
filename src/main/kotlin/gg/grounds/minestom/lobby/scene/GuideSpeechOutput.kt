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
    private val prepare: ((NpcPublicationGuard) -> NpcPresentationStart?)?,
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
    private var initialAlternateFrameNanos = 35_000_000L
    private var lastCallback: Long? = null
    private var completedPlan = false
    private var variationSeed = 0
    private val planner = GuideSpeechPlanner(bank)
    private var unavailable = false
    private var context: GuideSpeechContext? = null
    private var windows: List<GuideSpeechWindow> = emptyList()
    private var windowIndex = -1
    private var holdUntil: Long? = null
    private var lastRevealed = 0
    private var phaseStalled = false
    private var thoughtPlayed = false
    private var requireFollowingPhrase = false
    private var observedHesitation = false
    private val reservedThought = setOf("end01")

    fun beginSpeech(value: GuideSpeechContext, mood: GuideSpeechMood? = null) =
        synchronized(lock) {
            beginSpeech(value.timing.endNanos(), value.timing.graphemeDelayNanos(), mood)
            if (closed || !valid()) return@synchronized
            context = value
            val count = gg.grounds.dialogue.core.text.Graphemes.split(value.text).size
            windows =
                if (
                    count == value.total &&
                        value.revealed in 1 until count &&
                        value.timing.graphemeDelayNanos() > 0
                ) {
                    GuideSpeechProsody.windows(
                        value.text,
                        value.locale,
                        value.timing.graphemeDelayNanos(),
                        bank.clips
                            .filter { it.kind == "speech" }
                            .minOf { it.playbackNanos(1.035f) },
                        mood == GuideSpeechMood.GREETING,
                        if (mood == GuideSpeechMood.GREETING)
                            bank.clips
                                .filter { it.kind == "greeting" }
                                .minOf { it.playbackNanos(1.035f) } +
                                value.timing.graphemeDelayNanos()
                        else 0L,
                    )
                } else emptyList()
        }

    /**
     * Fresh successful presentation counts are authoritative; elapsed windows are never replayed.
     */
    fun reveal(text: String, revealed: Int) =
        synchronized(lock) {
            if (context == null) {
                reveal(text)
                return@synchronized
            }
            if (closed || !valid()) return@synchronized
            val value = context ?: return@synchronized
            if (revealed <= lastRevealed || revealed > value.total) return@synchronized
            val now = clock()
            val end = deadline ?: return@synchronized
            if (end - now <= 0) return@synchronized
            lastCallback?.let { previous ->
                val elapsed = now - previous
                if (elapsed > 250_000_000L) phaseStalled = true
                if (elapsed in 1_000_000L..100_000_000L) frameNanos = elapsed
            }
            lastCallback = now
            lastRevealed = revealed
            val delay = value.timing.graphemeDelayNanos()
            val selected =
                windows.indexOfFirst { window ->
                    revealed > window.startCount &&
                        revealed <= window.endCount &&
                        value.timing.enteredNanos() + window.endCount * delay - now > 0
                }
            if (selected >= 0 && selected >= windowIndex) {
                val window = windows[selected]
                val nominalStart = value.timing.enteredNanos() + window.startCount * delay
                val windowEnd = value.timing.enteredNanos() + window.endCount * delay
                val availableEnd = if (end - now < windowEnd - now) end else windowEnd
                if (windowIndex != selected) {
                    observedHesitation = windowIndex >= 0 && selected == windowIndex + 1
                    windowIndex = selected
                    completedPlan = false
                    requireFollowingPhrase = false
                    // Hold only the live boundary. Batched/skipped windows never queue older
                    // pauses.
                    val observedStart = if (nominalStart - now > 0) nominalStart else now
                    val pause =
                        if (
                            !phaseStalled &&
                                selected == windows.lastIndex &&
                                window.pauseNanos == 240_000_000L
                        )
                            shortTailPause(now, observedStart, availableEnd, window.pauseNanos)
                        else window.pauseNanos
                    holdUntil = if (window.startCount == 0) nominalStart else observedStart + pause
                }
                val ready = holdUntil?.let { now - it >= 0 } ?: true
                if (
                    !phaseStalled &&
                        !completedPlan &&
                        ready &&
                        (nextSound == null || now - nextSound!! >= 0)
                ) {
                    val initial =
                        selected == 0 &&
                            activeEnd == null &&
                            now - value.timing.enteredNanos() - delay in 0..200_000_000L
                    val cue =
                        when {
                            initial && phaseMood == GuideSpeechMood.GREETING && !greeted ->
                                phaseMood
                            initial && phaseMood == GuideSpeechMood.FAREWELL && !farewelled ->
                                phaseMood
                            else -> null
                        }
                    val prefixOnTime = now - nominalStart - window.pauseNanos <= 200_000_000L
                    val allowThought =
                        !thoughtPlayed &&
                            phaseMood == null &&
                            prefixOnTime &&
                            ((initial && value.node == "games") ||
                                (selected > 0 && window.hesitation && observedHesitation))
                    val thought =
                        bank.clips.firstOrNull { it.name == "end01" && it.kind == "ending" }
                    var voice: GuideVoice? = null
                    var isThought = false
                    var plan = emptyList<PlannedGuideVoice>()
                    if (allowThought && thought != null) {
                        val duration = thought.playbackNanos(1f)
                        val following =
                            planner.plan(
                                now + duration + 100_000_000L,
                                availableEnd,
                                frameNanos,
                                recentTalk.toList(),
                                null,
                                variationSeed,
                                { candidate -> candidate.key.contains("/speech/") },
                                reservedThought,
                            )
                        if (following.firstOrNull()?.voice?.key?.contains("/speech/") == true) {
                            voice = GuideVoice(thought.key, 1f, 1f, duration)
                            isThought = true
                        }
                    }
                    if (voice == null) {
                        plan =
                            planner.plan(
                                now,
                                availableEnd,
                                frameNanos,
                                recentTalk.toList(),
                                cue,
                                variationSeed,
                                if (requireFollowingPhrase)
                                    ({ candidate -> candidate.key.contains("/speech/") })
                                else null,
                                reservedThought,
                            )
                        voice = plan.firstOrNull()?.voice
                    }
                    if (voice == null) completedPlan = true
                    else if (
                        voice.durationNanos <=
                            availableEnd - now - GuideSpeechPlanner.END_GUARD_NANOS
                    ) {
                        stopVoice()
                        val played =
                            try {
                                play(voice.copy(volumeScale = voiceDraw(900..1000) / 1000f))
                                true
                            } catch (_: Exception) {
                                false
                            }
                        if (played) {
                            activeKey = voice.key
                            activeEnd = now + voice.durationNanos
                            nextSound = activeEnd!! + 40_000_000L
                            if (isThought) thoughtPlayed = true
                            requireFollowingPhrase = isThought
                            if (cue == GuideSpeechMood.GREETING) greeted = true
                            if (cue == GuideSpeechMood.FAREWELL) farewelled = true
                            val index = bank.clips.indexOfFirst { it.key == voice.key }
                            if (bank.clips[index].kind == "speech") {
                                recentTalk.addLast(index)
                                if (recentTalk.size > 2) recentTalk.removeFirst()
                            }
                            completedPlan = !isThought && plan.size == 1
                        } else completedPlan = true
                    }
                }
            }
            // Deliberate silence releases additive movement without stopping a complete live clip.
            speaking = activeEnd?.let { it - now > 0 } == true
            animate()
        }

    /** Keep the sentence pause unless only a shorter pause admits a complete final clip. */
    private fun shortTailPause(now: Long, observedStart: Long, end: Long, original: Long): Long {
        val frame = frameNanos.coerceIn(1_000_000L, 100_000_000L)
        for (pause in original downTo 120_000_000L step 20_000_000L) {
            val offset = maxOf(observedStart - now + pause, nextSound?.let { it - now } ?: 0L, 0L)
            val onset = now + ((offset + frame - 1) / frame) * frame
            if (
                planner
                    .plan(
                        onset,
                        end,
                        frame,
                        recentTalk.toList(),
                        null,
                        variationSeed,
                        excludedNames = reservedThought,
                    )
                    .isNotEmpty()
            )
                return pause
        }
        return original
    }

    fun beginSpeech(endNanos: Long, graphemeDelayNanos: Long, mood: GuideSpeechMood? = null) =
        synchronized(lock) {
            if (closed || !valid()) return@synchronized
            stop(false)
            deadline = endNanos
            frameNanos = maxOf(graphemeDelayNanos, 50_000_000L)
            initialAlternateFrameNanos = graphemeDelayNanos.coerceIn(1_000_000L, 100_000_000L)
            phaseMood = mood
            variationSeed = voiceDraw(0..Int.MAX_VALUE)
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
                val firstChecks = HashMap<Pair<String, Long>, Boolean>()
                val initialAllowed: ((GuideVoice) -> Boolean)? =
                    if (
                        activeEnd == null &&
                            frameNanos != initialAlternateFrameNanos &&
                            bank.clips.any { it.kind == "ending" }
                    ) {
                        { voice ->
                            firstChecks.getOrPut(voice.key to voice.durationNanos) {
                                if (end - now - voice.durationNanos <= 100_000_000L) true
                                else if (firstChecks.size >= 32) false
                                else {
                                    val index = bank.clips.indexOfFirst { it.key == voice.key }
                                    val history =
                                        if (bank.clips[index].kind == "speech")
                                            (recentTalk.toList() + index).takeLast(2)
                                        else recentTalk.toList()
                                    val alternate = initialAlternateFrameNanos
                                    var offset =
                                        ((voice.durationNanos + 40_000_000L + alternate - 1) /
                                            alternate) * alternate
                                    var fits = false
                                    while (!fits && offset <= voice.durationNanos + 100_000_000L) {
                                        val suffix =
                                            planner.plan(
                                                now + offset,
                                                end,
                                                alternate,
                                                history,
                                                null,
                                                variationSeed,
                                            )
                                        val last = suffix.lastOrNull()
                                        fits =
                                            last != null &&
                                                end - last.onsetNanos - last.voice.durationNanos in
                                                    GuideSpeechPlanner.END_GUARD_NANOS..100_000_000L
                                        offset += alternate
                                    }
                                    fits
                                }
                            }
                        }
                    } else null
                var plan =
                    planner.plan(
                        now,
                        end,
                        frameNanos,
                        recentTalk.toList(),
                        cue,
                        variationSeed,
                        initialAllowed,
                    )
                var choice = frameNanos
                val latest = activeEnd?.let { it - now + 100_000_000L }
                while (latest != null && choice <= latest) {
                    val alternative =
                        planner.plan(
                            now + choice,
                            end,
                            frameNanos,
                            recentTalk.toList(),
                            cue,
                            variationSeed,
                        )
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
            animate()
        }

    private fun animate() {
        if (!speaking) {
            optional { handle?.followSharedHeadLook() }
            return
        }
        if (reducedMotion()) {
            epoch++
            preparing?.let { optional { it.cancel() } }
            preparing = null
            optional { handle?.followSharedHeadLook() }
            optional { handle?.closeAsync() }
            handle = null
            published = null
            return
        }
        if (prepare != null && handle == null && preparing == null && !unavailable) begin()
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
            val start = prepare?.invoke(guard) ?: return@optional
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
            context = null
            windows = emptyList()
            windowIndex = -1
            holdUntil = null
            lastRevealed = 0
            phaseStalled = false
            requireFollowingPhrase = false
            observedHesitation = false
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
