package gg.grounds.minestom.lobby.scene

import gg.grounds.npc.minestom.presentation.*
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GuideSpeechOutputTest {
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
            output.beginSpeech(System.nanoTime() + 10_000_000_000L, 35_000_000L)
            output.reveal("a")
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
        fixture.begin()
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
        output.beginSpeech(System.nanoTime() + 10_000_000_000L, 35_000_000L)
        output.reveal("a")
        reduced = true
        output.reveal("b")
        assertEquals(1, fixture.handle.closes)
        val moves = fixture.handle.looks
        output.reveal("c")
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
        fixture.begin()
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
        fixture.begin()
        fixture.reveal("b")
        assertEquals(2, fixture.sounds)
    }

    @Test
    fun `punctuation silence settles the private head without cutting a live clip`() {
        val fixture = Fixture()
        val text = "A long complete first sentence. " + "Another complete sentence ".repeat(3)
        val count = gg.grounds.dialogue.core.text.Graphemes.split(text).size
        val entered = fixture.now - 35_000_000L
        val timing =
            gg.grounds.dialogue.core.runtime.TypingTiming(
                java.util.UUID(0, 0),
                1,
                1,
                35_000_000L,
                entered,
                entered + count * 35_000_000L,
            )
        fixture.output.beginSpeech(GuideSpeechContext(text, "en", "explain", count, 1, timing))
        fixture.output.reveal("A", 1)
        fixture.ready.complete(NpcPresentationResult.Ready(fixture.handle))
        assertEquals(1, fixture.handle.published)
        for (revealed in 2..32) {
            fixture.now = entered + revealed * 35_000_000L
            fixture.output.reveal("a", revealed)
        }
        assertTrue(
            fixture.handle.shared > 0,
            "Private head follows shared pose in punctuation silence",
        )
        fixture.stoppedAt.forEachIndexed { index, stop ->
            assertTrue(stop >= fixture.onsets[index] + fixture.voices[index].durationNanos)
        }
        val sounds = fixture.sounds
        fixture.output.stop(false)
        fixture.now += 300_000_000L
        fixture.output.reveal("a", 41)
        assertEquals(sounds, fixture.sounds)
    }

    @Test
    fun `failed Mhm playback does not consume the owner cue budget`() {
        val bank = testTimedGuideVoiceBank()
        var now = 35_000_000L
        var fail = true
        val voices = mutableListOf<GuideVoice>()
        val output =
            GuideSpeechOutput(
                { true },
                { true },
                { null },
                {
                    if (fail) throw IllegalStateException("optional transport unavailable")
                    voices += it
                },
                {},
                { now },
                bank = bank,
            )
        val text = "A long complete explanation ".repeat(6)
        val count = gg.grounds.dialogue.core.text.Graphemes.split(text).size
        fun begin(visit: Long) {
            val timing =
                gg.grounds.dialogue.core.runtime.TypingTiming(
                    java.util.UUID(0, 0),
                    visit,
                    1,
                    35_000_000L,
                    now - 35_000_000L,
                    now + count * 35_000_000L,
                )
            output.beginSpeech(GuideSpeechContext(text, "en", "games", count, 1, timing))
            output.reveal("A", 1)
        }
        begin(1)
        assertTrue(voices.isEmpty())
        fail = false
        now += 7_000_000_000L
        begin(2)
        assertEquals("dialogue:guide/ending/end01", voices.single().key)
        output.close()
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

        init {
            begin()
        }

        fun begin() = output.beginSpeech(now + 10_000_000_000L, 35_000_000L)

        fun reveal(text: String) = output.reveal(text)
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
