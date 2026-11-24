package gg.grounds.minestom.lobby.scene

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GuideSpeechPlanTest {
    @Test
    fun `whole phrase plan ends in final typing interval without repeating recent talk`() {
        val bank = testTimedGuideVoiceBank()
        val planner = GuideSpeechPlanner(bank)
        for (frame in listOf(35L, 50L)) {
            for (budget in listOf(2100L, 3500L, 5040L, 7000L)) {
                val plan =
                    planner.plan(
                        50_000_000,
                        budget * 1_000_000,
                        frame * 1_000_000,
                        listOf(0, 1),
                        null,
                    )
                assertTrue(plan.isNotEmpty(), "budget=$budget frame=$frame")
                val end = plan.last().onsetNanos + plan.last().voice.durationNanos
                assertTrue(
                    budget * 1_000_000 - end in 35_000_000..100_000_000,
                    "budget=$budget frame=$frame tail=${budget * 1_000_000 - end}",
                )
                plan.zipWithNext().forEach { (a, b) ->
                    assertTrue(
                        b.onsetNanos - a.onsetNanos - a.voice.durationNanos in
                            40_000_000..100_000_000
                    )
                }
                val history = mutableListOf(bank.clips[0].key, bank.clips[1].key)
                plan.forEach { clip ->
                    assertTrue(clip.voice.pitch in .965f..1.035f)
                    assertFalse(clip.voice.key in history.takeLast(2))
                    history += clip.voice.key
                }
            }
        }
    }

    @Test
    fun `all real Guide lines complete near authoritative deadline on natural frames`() {
        val bank =
            checkNotNull(
                GuideVoiceBank.load(StageDialogueAssets.load().entries()[GuideVoiceBank.PATH])
            )
        assertEquals(17, bank.clips.size)
        val failures = mutableListOf<String>()
        for ((locale, texts) in LobbyGuideContent.texts.locales()) {
            for ((key, text) in texts.filterKeys { !it.contains('.') }) {
                val mood =
                    when (key) {
                        "welcome" -> GuideSpeechMood.GREETING
                        "farewell" -> GuideSpeechMood.FAREWELL
                        else -> null
                    }
                val deadline = text.codePointCount(0, text.length) * 35_000_000L
                for (frame in listOf(35_000_000L, 50_000_000L)) for (phase in
                    listOf(0L, 7_000_000L, 34_000_000L)) for (seed in listOf(0, 1, 7, 42)) {
                    var now = phase + frame
                    val played = mutableListOf<Pair<Long, GuideVoice>>()
                    val output =
                        GuideSpeechOutput(
                            { true },
                            { true },
                            { error("reduced motion") },
                            { played += now to it },
                            {},
                            { now },
                            { it.first },
                            { range ->
                                if (range.first == 0 && range.last == Int.MAX_VALUE) seed
                                else range.first
                            },
                            bank,
                        )
                    output.beginSpeech(deadline, 35_000_000L, mood)
                    val first = now
                    var previous = 0
                    while (now < deadline) {
                        val revealed = (now / 35_000_000L).toInt().coerceAtMost(text.length - 1)
                        if (revealed > previous) {
                            output.reveal(text.substring(previous, revealed))
                            previous = revealed
                        }
                        now += frame
                    }
                    val last = played.lastOrNull()
                    val tail =
                        if (last == null) deadline
                        else deadline - last.first - last.second.durationNanos
                    if (played.firstOrNull()?.first != first || tail !in 35_000_000L..100_000_000L)
                        failures +=
                            "$locale.$key frame=$frame phase=$phase seed=$seed tail=$tail played=${played.map { it.second.key.substringAfterLast('/') }}"
                    played.zipWithNext().forEach { (a, b) ->
                        assertTrue(
                            b.first - a.first - a.second.durationNanos in 40_000_000L..100_000_000L
                        )
                    }
                    assertTrue(played.count { it.second.key.contains("/ending/") } <= 1)
                    if (played.any { it.second.key.contains("/ending/") })
                        assertTrue(played.last().second.key.contains("/ending/"))
                    output.close()
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `severely delayed reveal cannot replay a queued suffix and stop revokes the window`() {
        var now = 0L
        val played = mutableListOf<GuideVoice>()
        val output =
            GuideSpeechOutput(
                { true },
                { true },
                { error("reduced motion") },
                { played += it },
                {},
                { now },
                { it.first },
                { it.first },
                testTimedGuideVoiceBank(),
            )
        output.reveal("orphan callback")
        assertTrue(played.isEmpty())
        output.beginSpeech(3_500_000_000L, 35_000_000L)
        output.reveal("first")
        assertEquals(1, played.size)
        now = 2_000_000_000L
        output.reveal("delayed")
        assertEquals(1, played.size)
        output.stop(false)
        output.reveal("stale after skip")
        assertEquals(1, played.size)
        output.beginSpeech(now - 1, 35_000_000L)
        output.reveal("already complete")
        assertEquals(1, played.size)
        output.close()
    }

    @Test
    fun `greeting and farewell remain once per session across language and back navigation`() {
        var now = 0L
        val played = mutableListOf<GuideVoice>()
        val output =
            GuideSpeechOutput(
                { true },
                { true },
                { error("reduced motion") },
                { played += it },
                {},
                { now },
                { it.first },
                { it.first },
                testTimedGuideVoiceBank(),
            )
        for (mood in
            listOf(
                GuideSpeechMood.GREETING,
                GuideSpeechMood.GREETING,
                GuideSpeechMood.FAREWELL,
                GuideSpeechMood.FAREWELL,
            )) {
            output.beginSpeech(now + 2_000_000_000L, 35_000_000L, mood)
            output.reveal("naturally presented")
            output.stop(false)
            now += 3_000_000_000L
        }
        assertEquals(1, played.count { it.key.contains("/greeting/") })
        assertEquals(1, played.count { it.key.contains("/farewell/") })
        output.close()
    }

    @Test
    fun `fresh speech phases vary whole clip sequences including semantic cues`() {
        val bank =
            checkNotNull(
                GuideVoiceBank.load(StageDialogueAssets.load().entries()[GuideVoiceBank.PATH])
            )
        for (mood in listOf(null, GuideSpeechMood.GREETING, GuideSpeechMood.FAREWELL)) {
            val textKey =
                when (mood) {
                    GuideSpeechMood.GREETING -> "welcome"
                    GuideSpeechMood.FAREWELL -> "farewell"
                    null -> "games"
                }
            val deadline =
                checkNotNull(LobbyGuideContent.texts.locales()["en"]?.get(textKey)).length *
                    35_000_000L
            val sequences = mutableSetOf<List<String>>()
            val firstKeys = mutableSetOf<String>()
            for (seed in 0..7) {
                var seedDraws = 0
                var now = 50_000_000L
                val played = mutableListOf<Pair<Long, GuideVoice>>()
                val output =
                    GuideSpeechOutput(
                        { true },
                        { true },
                        { error("reduced motion") },
                        { played += now to it },
                        {},
                        { now },
                        { it.first },
                        { range ->
                            if (range.first == 0 && range.last == Int.MAX_VALUE) {
                                seedDraws++
                                seed
                            } else range.first
                        },
                        bank,
                    )
                output.beginSpeech(deadline, 35_000_000L, mood)
                while (now < deadline) {
                    output.reveal("a")
                    now += 50_000_000L
                }
                assertEquals(1, seedDraws, "Suffix planning must retain the begin seed")
                val last = played.last()
                assertTrue(
                    deadline - last.first - last.second.durationNanos in 35_000_000L..100_000_000L
                )
                played.zipWithNext().forEach { (a, b) ->
                    assertTrue(
                        b.first - a.first - a.second.durationNanos in 40_000_000L..100_000_000L
                    )
                }
                sequences += played.map { it.second.key }
                firstKeys += played.first().second.key
                output.close()
            }
            assertTrue(sequences.size > 1, "Fresh $mood phases must vary complete clip sequences")
            assertTrue(firstKeys.size > 1, "Fresh $mood phases must vary the first fitting cue")
        }
    }

    @Test
    fun `actual bank plans all lines across every distinct recent speech pair`() {
        val bank =
            checkNotNull(
                GuideVoiceBank.load(StageDialogueAssets.load().entries()[GuideVoiceBank.PATH])
            )
        val planner = GuideSpeechPlanner(bank)
        val talk = bank.clips.indices.filter { bank.clips[it].kind == "speech" }
        val failures = mutableListOf<String>()
        for ((locale, texts) in LobbyGuideContent.texts.locales()) for ((key, text) in
            texts.filterKeys { !it.contains('.') }) {
            val deadline = text.codePointCount(0, text.length) * 35_000_000L
            val mood =
                when (key) {
                    "welcome" -> GuideSpeechMood.GREETING
                    "farewell" -> GuideSpeechMood.FAREWELL
                    else -> null
                }
            for (a in talk) for (b in talk) if (a != b)
                for (frame in listOf(35_000_000L, 50_000_000L)) for (phase in
                    listOf(0L, 7_000_000L, 34_000_000L)) for (seed in listOf(0, 1, 7, 42)) {
                    val plan =
                        planner.plan(frame + phase, deadline, frame, listOf(a, b), mood, seed)
                    val history = mutableListOf(a, b)
                    for (planned in plan) {
                        val index = bank.clips.indexOfFirst { it.key == planned.voice.key }
                        if (bank.clips[index].kind == "speech") {
                            assertFalse(index in history.takeLast(2))
                            history += index
                        }
                    }
                    val last = plan.lastOrNull()
                    val tail =
                        if (last == null) deadline
                        else deadline - last.onsetNanos - last.voice.durationNanos
                    if (tail !in 35_000_000L..100_000_000L)
                        failures +=
                            "$locale.$key history=$a,$b frame=$frame phase=$phase seed=$seed tail=$tail"
                }
        }
        assertTrue(failures.isEmpty(), failures.take(30).joinToString("\n"))
    }
}

internal fun testTimedGuideVoiceBank(): GuideVoiceBank {
    val root =
        com.google.gson.JsonParser.parseString(testGuideVoiceMetadata().toString(Charsets.UTF_8))
            .asJsonObject
    root.addProperty("schemaVersion", 2)
    for ((name, duration) in listOf("end01" to 300, "end02" to 450, "end03" to 600)) {
        val entry = com.google.gson.JsonObject()
        entry.addProperty("kind", "ending")
        entry.addProperty("durationMillis", duration)
        root.getAsJsonObject("clips").add(name, entry)
    }
    return checkNotNull(GuideVoiceBank.load(root.toString().toByteArray()))
}
