package gg.grounds.minestom.lobby.scene

import gg.grounds.dialogue.core.runtime.TypingTiming
import gg.grounds.dialogue.core.text.Graphemes

internal data class GuideSpeechContext(
    val text: String,
    val locale: String,
    val node: String,
    val total: Int,
    val revealed: Int,
    val timing: TypingTiming,
)

internal data class GuideSpeechBoundary(
    val count: Int,
    val pauseMillis: Int,
    val hesitation: Boolean = false,
)

internal data class GuideSpeechWindow(
    val startCount: Int,
    val endCount: Int,
    val pauseNanos: Long,
    val hesitation: Boolean,
)

/** Immutable offsets use the same grapheme segmentation as the public typewriter contract. */
internal object GuideSpeechProsody {
    fun boundaries(text: String, locale: String): List<GuideSpeechBoundary> {
        val graphemes = Graphemes.split(text)
        val result = mutableListOf<GuideSpeechBoundary>()
        val abbreviations =
            when (locale.substringBefore('-').lowercase()) {
                "de" ->
                    setOf(
                        "dr.",
                        "prof.",
                        "z.b.",
                        "d.h.",
                        "bzw.",
                        "usw.",
                        "u.a.",
                        "ca.",
                        "nr.",
                        "str.",
                    )
                else ->
                    setOf(
                        "dr.",
                        "mr.",
                        "mrs.",
                        "ms.",
                        "prof.",
                        "e.g.",
                        "i.e.",
                        "etc.",
                        "vs.",
                        "no.",
                        "u.s.",
                    )
            }
        fun punctuation(value: String) =
            value in setOf(",", "，", "、", ":", ";", ".", "!", "?", "。", "！", "？", "…")
        var i = 0
        while (i < graphemes.size) {
            val value = graphemes[i]
            val previous = graphemes.getOrNull(i - 1).orEmpty()
            val next = graphemes.getOrNull(i + 1).orEmpty()
            val dash =
                value in setOf("—", "–", "-") &&
                    previous.isBlank() &&
                    next.isBlank() &&
                    i > 0 &&
                    i + 1 < graphemes.size
            if (!punctuation(value) && !dash) {
                i++
                continue
            }
            val tokenStart = (i - 1 downTo 0).firstOrNull { graphemes[it].isBlank() }?.plus(1) ?: 0
            val tokenEnd =
                (i until graphemes.size).firstOrNull { graphemes[it].isBlank() } ?: graphemes.size
            val fullToken = graphemes.subList(tokenStart, tokenEnd).joinToString("")
            val protectedToken =
                fullToken.trimEnd(
                    '.',
                    '!',
                    '?',
                    ',',
                    ':',
                    ';',
                    '…',
                    '"',
                    '\'',
                    '”',
                    '’',
                    '»',
                    ')',
                    ']',
                )
            val address =
                protectedToken.contains("://") ||
                    protectedToken.startsWith("www.") ||
                    (protectedToken.contains('@') &&
                        protectedToken.substringAfter('@').contains('.'))
            if (address && i < tokenStart + Graphemes.split(protectedToken).size) {
                i++
                continue
            }
            val ellipsis = value == "…" || (value == "." && next == ".")
            if (!ellipsis && !dash) {
                val numeric =
                    previous.firstOrNull()?.isDigit() == true &&
                        next.firstOrNull()?.isDigit() == true
                val internal =
                    (value == ":" && next == "/") ||
                        previous.isNotBlank() &&
                            next.isNotBlank() &&
                            (value == "." || value == ":") &&
                            next.firstOrNull()?.isLetterOrDigit() == true
                val token =
                    graphemes
                        .subList(tokenStart, i + 1)
                        .joinToString("")
                        .trimStart('(', '[', '“', '"')
                val abbreviation =
                    value == "." &&
                        (token.lowercase() in abbreviations ||
                            token.matches(Regex("[A-Z]\\.")) ||
                            token.matches(Regex("(?:[A-Za-z]\\.){2,}")))
                if (numeric || internal || abbreviation) {
                    i++
                    continue
                }
            }
            var end = i + 1
            if (!dash) while (end < graphemes.size && punctuation(graphemes[end])) end++
            val run = graphemes.subList(i, end).joinToString("")
            val hesitation = dash || run.contains("…") || run.contains("...")
            val pause =
                when {
                    hesitation -> 300
                    run.any { it in ".!?。！？" } -> 240
                    run.any { it in ":;" } -> 180
                    else -> 120
                }
            while (
                end < graphemes.size &&
                    graphemes[end] in
                        setOf(
                            "\"",
                            "'",
                            "”",
                            "’",
                            "“",
                            "‘",
                            "»",
                            "«",
                            "」",
                            "』",
                            "】",
                            "）",
                            "》",
                            "〉",
                            ")",
                            "]",
                            "}",
                        )
            ) end++
            result += GuideSpeechBoundary(end, pause, hesitation)
            i = end
        }
        return java.util.List.copyOf(result)
    }

    fun windows(
        text: String,
        locale: String,
        delay: Long,
        minimumPhrase: Long,
        greeting: Boolean = false,
        openingMinimum: Long = minimumPhrase,
    ): List<GuideSpeechWindow> {
        val graphemes = Graphemes.split(text)
        if (delay <= 0 || graphemes.isEmpty()) return emptyList()
        val points = boundaries(text, locale).toMutableList()
        if (greeting && points.size > 1) {
            val first = points.first()
            val salutation =
                graphemes
                    .take(first.count)
                    .joinToString("")
                    .trim()
                    .trimEnd('!', '.', '！', '。')
                    .lowercase()
            if (salutation in setOf("hi", "hallo", "hey", "hello")) {
                points.removeAt(0)
                while (
                    points.size > 1 &&
                        points.first().count * delay <
                            openingMinimum + GuideSpeechPlanner.END_GUARD_NANOS
                ) points.removeAt(0)
            }
        }
        var changed: Boolean
        do {
            changed = false
            for (index in points.indices) {
                val point = points[index]
                if (point.pauseMillis > 180 || point.count == graphemes.size) continue
                val previous = points.getOrNull(index - 1)
                val following = points.getOrNull(index + 1)?.count ?: graphemes.size
                val before =
                    (point.count - (previous?.count ?: 0)) * delay -
                        (previous?.pauseMillis ?: 0) * 1_000_000L
                val after = (following - point.count) * delay - point.pauseMillis * 1_000_000L
                if (minOf(before, after) < minimumPhrase + GuideSpeechPlanner.END_GUARD_NANOS) {
                    points.removeAt(index)
                    changed = true
                    break
                }
            }
        } while (changed)
        val windows = mutableListOf<GuideSpeechWindow>()
        var start = 0
        var pause = 0L
        var hesitation = false
        for (point in points) {
            windows += GuideSpeechWindow(start, point.count, pause, hesitation)
            start = point.count
            pause = point.pauseMillis * 1_000_000L
            hesitation = point.hesitation
        }
        if (
            start < graphemes.size &&
                graphemes.drop(start).any { it.codePoints().anyMatch(Character::isLetterOrDigit) }
        )
            windows += GuideSpeechWindow(start, graphemes.size, pause, hesitation)
        return java.util.List.copyOf(windows)
    }
}
