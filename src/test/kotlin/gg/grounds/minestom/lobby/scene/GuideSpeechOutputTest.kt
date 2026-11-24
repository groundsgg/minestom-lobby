package gg.grounds.minestom.lobby.scene

import gg.grounds.npc.minestom.presentation.*
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GuideSpeechOutputTest {
    @Test
    fun `natural letters throttle private sound while punctuation stays silent`() {
        val fixture = Fixture(reduced = true)
        fixture.reveal("Hi")
        fixture.reveal("a")
        assertEquals(1, fixture.sounds)
        fixture.now += 1_000_000_000L
        fixture.reveal("2")
        assertEquals(2, fixture.sounds)
        fixture.now += 1_000_000_000L
        fixture.reveal(" !")
        assertEquals(2, fixture.sounds)
        assertEquals(0, fixture.preparations)
        assertEquals(1, fixture.stops)
    }

    @Test
    fun `private motion pauses and resumes without changing another viewer`() {
        val a = Fixture()
        val b = Fixture()
        a.reveal("a")
        b.reveal("b")
        a.ready.complete(NpcPresentationResult.Ready(a.handle))
        b.ready.complete(NpcPresentationResult.Ready(b.handle))
        assertEquals(1, a.handle.published)
        assertEquals(1, b.handle.published)
        assertTrue(a.handle.looks > 0)
        a.output.stop(false)
        assertEquals(1, a.handle.shared)
        assertEquals(0, b.handle.shared)
        assertEquals(0, b.stops)
        a.output.close()
        assertEquals(1, a.handle.closes)
        assertEquals(0, b.handle.closes)
    }

    @Test
    fun `late private preparation after pause or close never publishes`() {
        for (terminal in listOf(false, true)) {
            val fixture = Fixture()
            fixture.reveal("a")
            fixture.output.stop(terminal)
            fixture.ready.complete(NpcPresentationResult.Ready(fixture.handle))
            assertEquals(0, fixture.handle.published)
            assertEquals(1, fixture.handle.closes)
            assertEquals(1, fixture.cancels)
        }
    }

    @Test
    fun `replaced connection and reduced motion suppress private animation`() {
        val fixture = Fixture()
        fixture.reveal("a")
        fixture.valid = false
        fixture.ready.complete(NpcPresentationResult.Ready(fixture.handle))
        fixture.reveal("b")
        assertEquals(0, fixture.handle.published)
        assertEquals(1, fixture.sounds)
        assertEquals(1, fixture.handle.closes)
        val reduced = Fixture(reduced = true)
        reduced.reveal("x")
        assertEquals(0, reduced.preparations)
        assertEquals(1, reduced.sounds)
    }

    @Test
    fun `optional output failures never interrupt speech lifecycle`() {
        val output =
            GuideSpeechOutput(
                { true },
                { false },
                { throw IllegalStateException() },
                { throw IllegalStateException() },
                { throw IllegalStateException() },
                bank = testGuideVoiceBank(),
            )
        assertDoesNotThrow {
            output.reveal("a", remainingNanos = 10_000_000_000L)
            output.stop(false)
            output.close()
        }
    }

    @Test
    fun `fallback releases private presentation and fresh speech can prepare again`() {
        val fixture = Fixture()
        fixture.reveal("a")
        fixture.ready.complete(NpcPresentationResult.Ready(fixture.handle))
        fixture.output.fallback()
        assertEquals(1, fixture.handle.closes)
        assertEquals(1, fixture.stops)
        fixture.now += 1_000_000_000L
        fixture.reveal("b")
        assertEquals(2, fixture.preparations)
    }

    @Test
    fun `enabling reduced motion releases a previously private head`() {
        var reduced = false
        val fixture = Fixture()
        val output =
            GuideSpeechOutput(
                { true },
                { reduced },
                {
                    object : NpcPresentationStart {
                        override fun ready() =
                            CompletableFuture.completedFuture<NpcPresentationResult>(
                                NpcPresentationResult.Ready(fixture.handle)
                            )

                        override fun cancel() = CompletableFuture.completedFuture<Void>(null)
                    }
                },
                {},
                {},
                bank = testGuideVoiceBank(),
            )
        output.reveal("a", remainingNanos = 10_000_000_000L)
        reduced = true
        output.reveal("b", remainingNanos = 10_000_000_000L)
        assertEquals(1, fixture.handle.closes)
        val moves = fixture.handle.looks
        output.reveal("c", remainingNanos = 10_000_000_000L)
        assertEquals(moves, fixture.handle.looks)
    }

    @Test
    fun `guide preferences preserve visual effects and honor explicit reduced motion`() {
        for (reduced in listOf(false, true)) {
            val preferences = LobbyDialogues.guidePreferences(reduced)
            assertEquals(reduced, preferences.reducedMotion())
            assertEquals("false", preferences.effects()["typingSound"])
            assertEquals("true", preferences.effects()["choiceSounds"])
            assertEquals("true", preferences.effects()["npcBackdrop"])
            assertEquals("true", preferences.effects()["vignette"])
            assertEquals("250", preferences.effects()["npcEffectsFadeMs"])
            assertEquals("0.35", preferences.effects()["npcVerticalOffset"])
        }
        assertEquals(java.time.Duration.ZERO, LobbyDialogues.defaultTheme().transition())
    }

    @Test
    fun `pause revokes a publication already waiting on the instance scheduler`() {
        val fixture = Fixture()
        fixture.handle.publication = CompletableFuture()
        fixture.reveal("a")
        fixture.ready.complete(NpcPresentationResult.Ready(fixture.handle))
        fixture.output.stop(false)
        var ran = false
        assertFalse(fixture.guard!!.runIfCurrent { ran = true })
        assertFalse(ran)
        fixture.handle.publication.complete(null)
        assertTrue(fixture.handle.closes >= 1)
    }

    @Test
    fun `cancelled preparation cannot disable motion in the resumed message`() {
        val fixture = Fixture()
        fixture.rejectCancellation = true
        fixture.reveal("a")
        fixture.output.stop(false)
        fixture.ready = CompletableFuture()
        fixture.reveal("b")
        assertEquals(2, fixture.preparations)
    }

    @Test
    fun `reduced motion revokes preparation before cancelling and can later resume`() {
        val fixture = Fixture()
        fixture.rejectCancellation = true
        fixture.reveal("a")
        fixture.reduced = true
        fixture.reveal("b")
        fixture.reduced = false
        fixture.ready = CompletableFuture()
        fixture.reveal("c")
        assertEquals(2, fixture.preparations)
    }

    @Test
    fun `voice cadence accepts a negative monotonic clock origin`() {
        val fixture = Fixture(reduced = true)
        fixture.now = -1_000_000_000L
        fixture.reveal("a")
        assertEquals(1, fixture.sounds)
        fixture.now += 1_000_000_000L
        fixture.reveal("b")
        assertEquals(2, fixture.sounds)
    }

    @Test
    fun `phrases must fit remaining text with a safety margin and are not replayed at the end`() {
        val fixture = Fixture(reduced = true)
        fixture.now = 0
        fixture.reveal("a", remainingNanos = 699_999_999L)
        assertEquals(0, fixture.sounds)
        fixture.reveal("a", remainingNanos = 700_000_000L)
        assertEquals(1, fixture.sounds)
        fixture.now = 700_000_000L
        fixture.reveal("last letters", remainingNanos = 100_000_000L)
        assertEquals(1, fixture.sounds)
        assertEquals(0, fixture.stops)
        fixture.output.stop(false)
        assertTrue(
            fixture.stoppedAt.single() >=
                fixture.onsets.single() + fixture.voices.single().durationNanos
        )
    }

    @Test
    fun `phrase duration pitch and breathing determine actual next onset`() {
        val fixture = Fixture(reduced = true, talkMillis = 1000, draws = listOf(40, 100))
        fixture.pitch = 965
        fixture.now = 0
        fixture.reveal("a")
        assertEquals(1_036_269_459L, fixture.voices.single().durationNanos)
        fixture.pitch = 1035
        fixture.now = 1_076_269_458L
        fixture.reveal("b")
        assertEquals(1, fixture.sounds)
        fixture.now++
        fixture.reveal("c")
        assertEquals(2, fixture.sounds)
        assertEquals(966_183_607L, fixture.voices.last().durationNanos)
        fixture.now = 2_142_453_065L
        fixture.reveal("d")
        assertEquals(2, fixture.sounds)
        fixture.now++
        fixture.reveal("e")
        assertEquals(3, fixture.sounds)
    }

    @Test
    fun `natural reveal never overlaps whole phrases never catches up and avoids last two samples`() {
        val fixture = Fixture(reduced = true)
        repeat(100) {
            fixture.now = it * 35_000_000L
            fixture.reveal("a")
        }
        assertTrue(fixture.sounds > 2)
        fixture.onsets.zipWithNext().forEachIndexed { index, (a, b) ->
            assertTrue(b - a >= fixture.voices[index].durationNanos + 40_000_000L)
        }
        fixture.voices.forEachIndexed { index, voice ->
            assertFalse(
                fixture.voices.subList(maxOf(0, index - 2), index).any { it.key == voice.key }
            )
        }
        val before = fixture.sounds
        fixture.now += 20_000_000_000L
        repeat(10) { fixture.reveal("newly revealed text") }
        assertEquals(before + 1, fixture.sounds)
    }

    @Test
    fun `punctuation extends a phrase pause while spaces never cut and explicit stop resets it`() {
        val fixture = Fixture(reduced = true, draws = listOf(40, 240, 40, 40))
        fixture.now = -1_000_000_000L
        fixture.reveal("a")
        fixture.now = -500_000_000L
        fixture.reveal(".")
        fixture.reveal(" ")
        assertEquals(0, fixture.stops)
        fixture.now = -260_000_001L
        fixture.reveal("b")
        assertEquals(1, fixture.sounds)
        fixture.now++
        fixture.reveal("c")
        assertEquals(2, fixture.sounds)
        fixture.output.stop(false)
        fixture.reveal("new node")
        assertEquals(3, fixture.sounds)
        fixture.output.close()
        fixture.now += 10_000_000_000L
        fixture.reveal("goodbye", GuideSpeechMood.FAREWELL)
        assertEquals(3, fixture.sounds)
    }

    @Test
    fun `fitting semantic cues play once across locale node and lifecycle changes`() {
        val fixture = Fixture(reduced = true, moodMillis = 900)
        fixture.now = 0
        fixture.reveal(" ", GuideSpeechMood.GREETING)
        fixture.reveal("Hello", GuideSpeechMood.GREETING, 999_999_999L)
        assertEquals(0, fixture.sounds)
        fixture.reveal("Hello", GuideSpeechMood.GREETING, 1_000_000_000L)
        assertEquals("dialogue:guide/greeting/hello01", fixture.voices.single().key)
        fixture.now = 939_999_999L
        fixture.reveal("more words", remainingNanos = 10_000_000_000L)
        assertEquals(1, fixture.sounds)
        fixture.now++
        fixture.reveal("more words")
        assertEquals(2, fixture.sounds)
        fixture.output.stop(false)
        fixture.reveal("Hallo", GuideSpeechMood.GREETING)
        assertEquals(1, fixture.voices.count { it.key.contains("greeting") })
        fixture.now += 2_000_000_000L
        fixture.reveal("Bye", GuideSpeechMood.FAREWELL, 1_000_000_000L)
        assertEquals(1, fixture.voices.count { it.key.contains("farewell") })
        fixture.output.stop(false)
        fixture.reveal("Bis bald", GuideSpeechMood.FAREWELL)
        assertEquals(1, fixture.voices.count { it.key.contains("farewell") })
        fixture.output.close()
        assertEquals(fixture.voices.map { it.key }, fixture.stoppedKeys)
    }

    @Test
    fun `missing voice metadata preserves the generic typing fallback`() {
        assertEquals("true", LobbyDialogues.guidePreferences(false, false).effects()["typingSound"])
        assertEquals("false", LobbyDialogues.guidePreferences(false, true).effects()["typingSound"])
    }

    @Test
    fun `selection excludes overlong phrases and does not repeat recent clips to force a fit`() {
        val metadata =
            com.google.gson.JsonParser.parseString(
                    testGuideVoiceMetadata(talkMillis = 1800).toString(Charsets.UTF_8)
                )
                .asJsonObject
        metadata.getAsJsonObject("clips").getAsJsonObject("bwa").addProperty("durationMillis", 600)
        metadata.getAsJsonObject("clips").getAsJsonObject("li").addProperty("durationMillis", 700)
        val bank = checkNotNull(GuideVoiceBank.load(metadata.toString().toByteArray()))
        val fixture = Fixture(reduced = true, voiceBank = bank)
        fixture.now = 0
        fixture.pitch = 965
        fixture.reveal("a", remainingNanos = 700_000_000L)
        assertEquals(0, fixture.sounds)
        fixture.pitch = 1000
        fixture.reveal("a", remainingNanos = 700_000_000L)
        fixture.now = 2_000_000_000L
        fixture.reveal("b", remainingNanos = 800_000_000L)
        fixture.now = 4_000_000_000L
        fixture.reveal("c", remainingNanos = 800_000_000L)
        assertEquals(listOf("bwa", "li"), fixture.voices.map { it.key.substringAfterLast('/') })
    }

    @Test
    fun `sentence and clause punctuation adds only a short pause without cutting a phrase`() {
        for (punctuation in ".,!?:;…") {
            val fixture = Fixture(reduced = true, draws = listOf(40, 240, 40))
            fixture.now = 0
            fixture.reveal("a")
            fixture.now = 600_000_000L
            fixture.reveal(punctuation.toString())
            fixture.reveal(" ")
            assertEquals(0, fixture.stops)
            fixture.now = 839_999_999L
            fixture.reveal("b")
            assertEquals(1, fixture.sounds, punctuation.toString())
            fixture.now++
            fixture.reveal("c")
            assertEquals(2, fixture.sounds, punctuation.toString())
        }
    }

    @Test
    fun `word punctuation does not insert a sentence pause`() {
        for (punctuation in listOf("'", "’", "-", "/")) {
            val fixture = Fixture(reduced = true)
            fixture.now = 0
            fixture.reveal("a")
            fixture.now = 590_000_000L
            fixture.reveal(punctuation)
            fixture.now = 700_000_000L
            fixture.reveal("b")
            assertEquals(2, fixture.sounds, punctuation)
            assertEquals(1, fixture.stops, "Only the next whole phrase stops the finished key")
        }
    }

    @Test
    fun `real long German Guide line keeps whole phrase gaps below 200 milliseconds`() {
        val bank =
            checkNotNull(
                GuideVoiceBank.load(StageDialogueAssets.load().entries()[GuideVoiceBank.PATH])
            )
        val text =
            "Mit dem Kompass im ersten Hotbar-Slot kannst du die verfügbaren Spiele ansehen und einen Modus auswählen."
        for (seed in listOf(1, 7, 17, 42, 99)) {
            val random = kotlin.random.Random(seed)
            var now = 0L
            val played = mutableListOf<Pair<Long, GuideVoice>>()
            val output =
                GuideSpeechOutput(
                    { true },
                    { true },
                    { error("Reduced motion") },
                    { played += now to it },
                    {},
                    { now },
                    { random.nextInt(it.first, it.last + 1) },
                    { random.nextInt(it.first, it.last + 1) },
                    bank,
                )
            // First presentation establishes the baseline; the final WAITING frame is silent.
            for (index in 1 until text.lastIndex) {
                now = (index + 1) * 35_000_000L
                output.reveal(
                    text[index].toString(),
                    remainingNanos = (text.length - index - 1) * 35_000_000L,
                )
            }
            assertTrue(played.size >= 2, "seed=$seed")
            played.zipWithNext().forEach { (a, b) ->
                val gap = b.first - a.first - a.second.durationNanos
                assertTrue(gap in 40_000_000L..200_000_000L, "seed=$seed gap=$gap")
            }
            played.forEach { (onset, voice) ->
                val clip = bank.clips.single { it.key == voice.key }
                assertEquals(clip.playbackNanos(voice.pitch), voice.durationNanos)
                assertTrue(onset + voice.durationNanos + 100_000_000L <= text.length * 35_000_000L)
            }
            output.close()
        }
    }

    private class Fixture(
        var reduced: Boolean = false,
        talkMillis: Int = 600,
        moodMillis: Int = 500,
        draws: List<Int> = emptyList(),
        voiceBank: GuideVoiceBank = testGuideVoiceBank(talkMillis, moodMillis),
    ) {
        private val draws = java.util.ArrayDeque(draws)
        val onsets = mutableListOf<Long>()
        val voices = mutableListOf<GuideVoice>()
        val stoppedKeys = mutableListOf<String>()
        val stoppedAt = mutableListOf<Long>()
        var valid = true
        var now = 1_000_000_000L
        var sounds = 0
        var stops = 0
        var preparations = 0
        var cancels = 0
        var pitch = 1000
        var rejectCancellation = false
        var guard: gg.grounds.npc.minestom.spi.NpcPublicationGuard? = null
        val handle = Handle()
        var ready = CompletableFuture<NpcPresentationResult>()
        val output =
            GuideSpeechOutput(
                { valid },
                { reduced },
                { publicationGuard ->
                    guard = publicationGuard
                    preparations++
                    object : NpcPresentationStart {
                        override fun ready() = ready

                        override fun cancel(): java.util.concurrent.CompletionStage<Void> {
                            cancels++
                            if (rejectCancellation)
                                ready.completeExceptionally(
                                    java.util.concurrent.CancellationException()
                                )
                            return CompletableFuture.completedFuture(null)
                        }
                    }
                },
                { voice ->
                    voices += voice
                    sounds++
                    onsets += now
                },
                { key ->
                    stoppedKeys += key
                    stoppedAt += now
                    stops++
                },
                { now },
                { range ->
                    val value = this.draws.pollFirst() ?: range.first
                    assertTrue(value in range, "Unexpected rhythm draw $value for $range")
                    value
                },
                { range ->
                    when (range) {
                        965..1035 -> pitch
                        900..1000 -> 1000
                        else -> range.first
                    }
                },
                bank = voiceBank,
            )

        fun reveal(
            text: String,
            mood: GuideSpeechMood? = null,
            remainingNanos: Long = Long.MAX_VALUE,
        ) = output.reveal(text, mood, remainingNanos)
    }

    private class Handle : NpcPresentationHandle {
        var published = 0
        var looks = 0
        var shared = 0
        var closes = 0

        var publication = CompletableFuture.completedFuture<Void>(null)

        private fun done() = CompletableFuture.completedFuture<Void>(null)

        override fun publish() = publication.also { published++ }

        override fun applyHeadLook(yawDegrees: Double, pitchDegrees: Double) =
            done().also {
                assertTrue(kotlin.math.abs(yawDegrees) <= 2.5)
                assertTrue(kotlin.math.abs(pitchDegrees) <= 1.5)
                looks++
            }

        override fun followSharedHeadLook() = done().also { shared++ }

        override fun closeAsync() = done().also { closes++ }

        override fun playClip(clipId: String) = done()

        override fun stopClip() = done()

        override fun followSharedClip() = done()

        override fun closed() = CompletableFuture<Void>()

        override fun snapshot(): NpcPresentationSnapshot = error("Snapshot unused")
    }
}
