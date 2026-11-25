package gg.grounds.minestom.lobby.scene

import gg.grounds.dialogue.core.runtime.*
import gg.grounds.dialogue.core.text.Graphemes
import gg.grounds.dialogue.core.validation.ContentPin
import gg.grounds.dialogue.minestom.*
import gg.grounds.scene.format.LocalId
import java.time.Duration
import java.util.UUID
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.network.packet.server.play.SoundEffectPacket
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class GuideSpeechProsodyListenerTest {
    @Test
    fun `production callbacks leave punctuation silence and contain every whole voice`() {
        Harness().use { h ->
            val text =
                "A complete long welcoming phrase here. Another sufficiently long explanation follows here."
            h.begin(text)
            h.finish()
            val boundary = Graphemes.split(text.substringBefore('.') + ".").size * h.delay
            assertTrue(h.played.any { it.first < boundary })
            assertTrue(h.played.any { it.first > boundary })
            for ((onset, voice) in h.played) {
                val end = onset + h.duration(voice, onset)
                if (onset < boundary) assertTrue(end <= boundary - 35_000_000L)
                else assertTrue(onset >= boundary + 240_000_000L)
                assertTrue(end <= h.end - 35_000_000L)
            }
            assertTrue(h.played.none { it.second.endsWith("/end01") })
        }
    }

    @Test
    fun `actual counts gate future windows even when delta contains only one letter`() {
        Harness().use { h ->
            val first = "Long enough opening explanation here."
            h.begin(first + " Another long and complete explanation here.")
            for (count in 2..70) h.present(count * h.delay, 1)
            assertTrue(h.played.all { it.first < Graphemes.split(first).size * h.delay })
            h.present(71 * h.delay, 71)
            assertEquals(1, h.played.size, "A stalled presentation cannot revive a skipped suffix")
        }
    }

    @Test
    fun `batched presentation cannot release a window before its nominal pause ends`() {
        Harness().use { h ->
            val first = "Short."
            h.begin(first + " Another explanation ".repeat(6))
            val boundary = Graphemes.split(first).size
            for (step in 2..12) h.present(step * h.delay, boundary + step)
            assertEquals(0, h.played.size, "Actual counts also require the nominal pause deadline")
        }
    }

    @Test
    fun `stall remains suppressed when a new punctuation window is entered`() {
        Harness().use { h ->
            h.begin("Long enough first explanation here. " + "Next explanation ".repeat(8))
            h.present(2 * h.delay, 2)
            h.present(1_500_000_000L, 43)
            h.finish(44)
            assertEquals(1, h.played.size)
        }
    }

    @Test
    fun `late begin does not replay greeting or thoughtful prefix`() {
        for (node in listOf("start", "games")) Harness().use { h ->
            h.begin("A long introduction ".repeat(8), node, initial = 25, at = 875_000_000L)
            h.finish(26)
            assertTrue(
                h.played.none { it.second.contains("/greeting/") || it.second.endsWith("/end01") }
            )
        }
    }

    @Test
    fun `begin after a skipped hesitation never queues a retrospective cue`() {
        Harness().use { h ->
            val prefix = "An entire skipped explanation ".repeat(4) + "…"
            val initial = Graphemes.split(prefix).size + 2
            h.begin(
                prefix + " A new explanation ".repeat(5),
                initial = initial,
                at = initial * h.delay,
            )
            h.finish(initial + 1)
            assertTrue(h.played.none { it.second.endsWith("/end01") })
        }
    }

    @Test
    fun `games Mhm plays only once across locale refresh and backwards navigation`() {
        Harness().use { h ->
            val text = "A detailed explanation ".repeat(7)
            h.begin(text, "games")
            h.finish()
            val firstVisitCount = h.played.size
            h.listener.invalidate("de")
            h.begin(text, "games", locale = "de", visit = 2, at = h.end + h.delay)
            h.finish()
            assertTrue(
                h.played.size > firstVisitCount,
                "Locale refresh must actually present fresh speech",
            )
            val refreshedCount = h.played.size
            h.begin(text, "games", locale = "de", visit = 3, at = h.end + h.delay)
            h.finish()
            assertTrue(
                h.played.size > refreshedCount,
                "Back navigation must actually present fresh speech",
            )
            assertEquals(1, h.played.count { it.second.endsWith("/end01") })
            val cue = h.played.first()
            assertTrue(cue.second.endsWith("/end01"))
            assertTrue(h.played[1].second.contains("/speech/"))
            assertTrue(h.played[1].first - cue.first >= h.duration(cue.second) + 40_000_000L)
        }
    }

    @Test
    fun `interior hesitation allows one cue but final punctuation and short lines do not`() {
        for ((text, expected) in
            listOf(
                ("A full explanation ".repeat(3) + "… " + "Another full explanation ".repeat(4)) to
                    1,
                ("A full explanation ".repeat(5) + "…") to 0,
                "Short, line." to 0,
            )) Harness().use { h ->
            h.begin(text)
            h.finish()
            assertEquals(expected, h.played.count { it.second.endsWith("/end01") }, text)
        }
    }

    @Test
    fun `negative origin and batched actual counts preserve boundaries`() {
        Harness(origin = -10_000_000_000L).use { h ->
            h.begin("é👩‍👩‍👧‍👦 " + "A long phrase ".repeat(4) + "。" + "另一段文字".repeat(10))
            h.finish(step = 2)
            assertTrue(h.played.size > 1)
            assertTrue(
                h.played.all { (time, key) -> time + h.duration(key, time) <= h.end - 35_000_000L }
            )
        }
    }

    @Test
    fun `paused or replaced phase rejects a queued old begin`() {
        for (reason in
            listOf(
                MinestomDialogueSpeechListener.StopReason.PAUSED,
                MinestomDialogueSpeechListener.StopReason.CONTENT_REPLACED,
                MinestomDialogueSpeechListener.StopReason.PRESENTATION_FALLBACK,
            )) Harness().use { h ->
            val text = "A full explanation ".repeat(5)
            h.begin(text)
            h.listener.onStop(reason)
            val before = h.played.size
            h.begin(text)
            h.finish()
            assertEquals(before, h.played.size, reason.toString())
            h.begin(text, visit = 2, at = h.end + h.delay)
            assertTrue(h.played.size > before)
        }
    }

    @Test
    fun `stop during pending pause and invalidated begin cannot revive output`() {
        Harness().use { h ->
            val first = "A long opening explanation here."
            h.begin(first + " Another long explanation that must stay silent.")
            val boundary = Graphemes.split(first).size
            for (count in 2..boundary) h.present(count * h.delay, count)
            h.listener.invalidate("de")
            val before = h.played.size
            h.finish(boundary + 1)
            assertEquals(before, h.played.size)
            h.listener.onStop(MinestomDialogueSpeechListener.StopReason.SESSION_ENDED)
        }
    }

    @Test
    fun `real English and German welcomes retain a complete greeting`() {
        for ((locale, texts) in LobbyGuideContent.texts.locales()) Harness().use { h ->
            h.begin(texts.getValue("welcome"), "start", locale = locale)
            h.finish()
            assertTrue(
                h.played.firstOrNull()?.second?.contains("/greeting/") == true,
                "$locale ${h.played}",
            )
            assertTrue(
                h.played.sumOf { h.duration(it.second, it.first) } >=
                    h.bank.clips
                        .filter { it.kind == "greeting" }
                        .minOf { it.playbackNanos(1.035f) },
                "$locale welcome voice coverage",
            )
            assertTrue(h.played.none { it.second.endsWith("/end01") })
        }
    }

    @Test
    fun `every real line preserves whole clips recent pair gaps and private output`() {
        for ((locale, texts) in LobbyGuideContent.texts.locales()) for ((key, text) in
            texts.filterKeys { !it.contains('.') }) for (seed in listOf(0, 7, 42)) for (frame in
            listOf(35_000_000L, 50_000_000L)) Harness(seed = seed).use { h ->
            h.begin(text, if (key == "welcome") "start" else key, locale = locale, at = frame)
            var elapsed = frame * 2
            while (elapsed < h.end) {
                h.present(elapsed, (elapsed / h.delay).toInt())
                elapsed += frame
            }
            val windows =
                GuideSpeechProsody.windows(
                    text,
                    locale,
                    h.delay,
                    h.bank.clips.filter { it.kind == "speech" }.minOf { it.playbackNanos(1.035f) },
                    key == "welcome",
                )
            val history = mutableListOf<String>()
            h.played.forEach { (time, voice) ->
                assertTrue(
                    time + h.duration(voice, time) <= h.end - 35_000_000L,
                    "$locale.$key $frame $seed",
                )
                if (voice.contains("/speech/")) {
                    assertFalse(voice in history.takeLast(2), "$locale.$key $voice repeated")
                    history += voice
                }
            }
            h.played.zipWithNext().forEach { (a, b) ->
                val sameWindow =
                    windows.any {
                        a.first >= it.startCount * h.delay && b.first <= it.endCount * h.delay
                    }
                val gap = b.first - a.first - h.duration(a.second, a.first)
                assertTrue(gap >= 40_000_000L, "$locale.$key gap=$gap")
                if (sameWindow)
                    assertTrue(
                        gap <= 100_000_000L,
                        "$locale.$key frame=$frame seed=$seed gap=$gap ${h.played}",
                    )
            }
        }
    }

    @Test
    fun `zero delay malformed counts and completed begin remain silent`() {
        Harness().use { h ->
            h.begin("Long enough explanation ".repeat(5), delayOverride = 0)
            h.finish()
            assertTrue(h.played.isEmpty())
        }
        Harness().use { h ->
            val text = "Long enough explanation ".repeat(3)
            h.begin(text, initial = Graphemes.split(text).size)
            assertTrue(h.played.isEmpty())
        }
        Harness().use { h ->
            h.begin("Long enough explanation ".repeat(4), totalOverride = 9)
            h.finish()
            assertTrue(h.played.isEmpty())
        }
    }

    @Test
    fun `disconnected owner and same UUID replacement never receive delayed speech`() {
        Harness().use { h ->
            h.begin("A complete phrase here. " + "Another explanation ".repeat(6))
            val neighbor = h.fixture.player(Pos.ZERO, h.player.uuid)
            h.player.remove()
            val before = h.played.size
            h.finish()
            assertEquals(before, h.played.size)
            assertTrue(neighbor.connection.packets.none { it is SoundEffectPacket })
        }
    }

    private class Harness(val origin: Long = 0L, val seed: Int = 0) : AutoCloseable {
        val fixture = RendererFixture()
        val player = fixture.player(Pos.ZERO)
        val delay = 35_000_000L
        var now = origin
        var entered = origin
        var end = origin
        var text = ""
        var node = "explain"
        var locale = "en"
        var visit = 1L
        var totalOverride: Int? = null
        val bank = GuideVoiceBank.load(StageDialogueAssets.load().entries()[GuideVoiceBank.PATH])!!
        val played = mutableListOf<Pair<Long, String>>()
        private var packets = 0
        private val binding =
            LobbyDialogueBinding(LocalId("guide"), Pos.ZERO, LobbyGuideContent.DIALOGUE_ID, "guide")
        private val speaker =
            MinestomSpeakerBinding("test", fixture.instance) {
                MinestomSpeakerBinding.AnchorSnapshot(Pos.ZERO, 0f, Pos(0.0, 1.6, 0.0), true)
            }
        val listener =
            GuideSpeechListener(
                player,
                fixture.instance,
                binding,
                { true },
                { true },
                { error("reduced motion") },
                bank,
                Duration.ofMillis(35),
                { now },
                { it.first },
                { if (it.last == Int.MAX_VALUE) seed else it.first },
            )

        private val durations = mutableMapOf<Pair<Long, String>, Long>()

        fun duration(key: String, onset: Long? = null): Long =
            if (onset != null) durations.getValue(onset to key)
            else durations.entries.first { it.key.second == key }.value

        private fun view(count: Int) =
            DialogueView(
                UUID(0, 1),
                ContentPin("0".repeat(64)),
                node,
                visit,
                DialogueView.State.REVEALING,
                1,
                1,
                locale,
                "guide",
                text,
                emptyList(),
                null,
                (totalOverride ?: Graphemes.split(text).size),
                count,
                DialogueView.HintPhase.REVEAL,
                "",
                null,
            )

        fun begin(
            value: String,
            node: String = "explain",
            initial: Int = 1,
            at: Long = origin + delay,
            locale: String = "en",
            visit: Long = 1,
            delayOverride: Long = delay,
            totalOverride: Int? = null,
        ) {
            this.totalOverride = totalOverride
            text = value
            this.node = node
            this.locale = locale
            this.visit = visit
            entered = at - initial * delay
            now = at
            end = entered + Graphemes.split(text).size * delay
            val view = view(initial)
            listener.onBegin(
                MinestomDialogueSpeechBeginEvent(
                    view,
                    "guide",
                    speaker,
                    TypingTiming(view.sessionId(), visit, 1, delayOverride, entered, end),
                )
            )
            collect()
        }

        fun present(at: Long, count: Int) {
            now = entered + at
            listener.onRevealed(MinestomDialogueSpeechEvent(view(count), "guide", speaker, "a"))
            collect()
        }

        fun finish(start: Int = 2, step: Int = 1) {
            for (count in start until Graphemes.split(text).size step step) present(
                count * delay,
                count,
            )
        }

        private fun collect() {
            val sounds = player.connection.packets.filterIsInstance<SoundEffectPacket>()
            sounds.drop(packets).forEach {
                val key = it.soundEvent().key().asString()
                played += now to key
                durations[now to key] =
                    bank.clips.single { clip -> clip.key == key }.playbackNanos(it.pitch())
                assertTrue(it.pitch() in .965f..1.035f)
            }
            packets = sounds.size
        }

        override fun close() {
            listener.close()
            fixture.close()
        }
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun init() {
            MinecraftServer.init()
        }
    }
}
