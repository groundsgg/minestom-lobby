package gg.grounds.minestom.lobby.scene

import kotlin.math.ceil

internal data class PlannedGuideVoice(val onsetNanos: Long, val voice: GuideVoice)

/** Bounded search over whole clips. Only unplayed suffixes may be planned again. */
internal class GuideSpeechPlanner(private val bank: GuideVoiceBank) {
    fun plan(
        now: Long,
        deadline: Long,
        frameNanos: Long,
        recent: List<Int>,
        mood: GuideSpeechMood?,
        variationSeed: Int = 0,
        firstVoiceAllowed: ((GuideVoice) -> Boolean)? = null,
    ): List<PlannedGuideVoice> {
        val budget = deadline - now - END_GUARD_NANOS
        if (budget <= 0 || budget > MAX_WINDOW_NANOS) return emptyList()
        val frame = frameNanos.coerceIn(1_000_000L, 100_000_000L)
        val target = budget
        val initialKind =
            when (mood) {
                GuideSpeechMood.GREETING -> "greeting"
                GuideSpeechMood.FAREWELL -> "farewell"
                null -> "speech"
            }
        data class State(val onset: Long, val recent: List<Int>, val kind: String)
        data class Result(val end: Long, val clips: List<PlannedGuideVoice>)
        // A phase seed keeps preferences stable while its unplayed suffix is revised.
        val orderedClips =
            bank.clips.withIndex().toList().let {
                if (variationSeed == 0) it else it.shuffled(kotlin.random.Random(variationSeed))
            }
        val cache = HashMap<State, Result>()
        var states = 0
        fun voice(clip: GuideVoiceClip, duration: Long): GuideVoice? {
            var pitch =
                (clip.durationMillis * 1_000_000.0 / duration).toFloat().coerceIn(.965f, 1.035f)
            if (clip.playbackNanos(pitch) > duration && pitch < 1.035f) pitch = Math.nextUp(pitch)
            val actual = clip.playbackNanos(pitch)
            if (actual > duration) return null
            return GuideVoice(clip.key, pitch, 1f, actual)
        }
        fun solve(onset: Long, history: List<Int>, kind: String, depth: Int): Result {
            val state = State(onset, history, kind)
            cache[state]?.let {
                return it
            }
            if (++states > MAX_STATES || depth > MAX_CLIPS) return Result(0L, emptyList())
            var best = Result(0L, emptyList())
            val candidates =
                orderedClips.filter { (index, clip) ->
                    (clip.kind == kind || (kind == "speech" && clip.kind == "ending")) &&
                        (clip.kind != "speech" || index !in history)
                }
            for ((index, clip) in candidates) {
                val minimum = clip.playbackNanos(1.035f)
                val maximum = clip.playbackNanos(.965f)
                if (onset + minimum > target) continue
                val duration = minOf(maximum, target - onset)
                val terminal = voice(clip, duration) ?: continue
                val end = onset + terminal.durationNanos
                if (depth != 0 || firstVoiceAllowed?.invoke(terminal) != false) {
                    if (end > best.end)
                        best = Result(end, listOf(PlannedGuideVoice(onset, terminal)))
                    if (target - end <= END_FIT_TOLERANCE_NANOS)
                        return best.also { cache[state] = it }
                }
                if (clip.kind == "ending") continue
                val nextHistory =
                    if (clip.kind == "speech") (history + index).takeLast(2) else history
                val earliest = onset + minimum + MIN_GAP_NANOS
                val latest = minOf(target, onset + maximum + MAX_GAP_NANOS)
                var next = ceil(earliest.toDouble() / frame).toLong() * frame
                while (next <= latest) {
                    val low = maxOf(minimum, next - onset - MAX_GAP_NANOS)
                    val high = minOf(maximum, next - onset - MIN_GAP_NANOS)
                    if (low <= high) {
                        val current = voice(clip, (next - onset - 70_000_000L).coerceIn(low, high))
                        if (
                            current != null &&
                                next - onset - current.durationNanos <= MAX_GAP_NANOS &&
                                (depth != 0 || firstVoiceAllowed?.invoke(current) != false)
                        ) {
                            val suffix = solve(next, nextHistory, "speech", depth + 1)
                            if (suffix.clips.isNotEmpty() && suffix.end > best.end) {
                                best =
                                    Result(
                                        suffix.end,
                                        listOf(PlannedGuideVoice(onset, current)) + suffix.clips,
                                    )
                                if (target - best.end <= END_FIT_TOLERANCE_NANOS)
                                    return best.also { cache[state] = it }
                            }
                        }
                    }
                    next += frame
                }
            }
            cache[state] = best
            return best
        }
        return solve(0L, recent.takeLast(2), initialKind, 0).clips.map {
            it.copy(onsetNanos = now + it.onsetNanos)
        }
    }

    companion object {
        internal const val END_GUARD_NANOS = 35_000_000L
        private const val END_FIT_TOLERANCE_NANOS = 65_000_000L
        private const val MIN_GAP_NANOS = 40_000_000L
        private const val MAX_GAP_NANOS = 100_000_000L
        private const val MAX_STATES = 8192
        private const val MAX_CLIPS = 256
        private const val MAX_WINDOW_NANOS = 120_000_000_000L
    }
}
