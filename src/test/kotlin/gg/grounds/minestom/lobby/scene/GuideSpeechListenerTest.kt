package gg.grounds.minestom.lobby.scene

import gg.grounds.dialogue.core.runtime.*
import gg.grounds.dialogue.core.validation.ContentPin
import gg.grounds.dialogue.minestom.*
import gg.grounds.scene.format.LocalId
import java.time.Duration
import java.util.UUID
import net.kyori.adventure.sound.Sound
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.network.packet.server.play.SoundEffectPacket
import net.minestom.server.network.packet.server.play.StopSoundPacket
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class GuideSpeechListenerTest {
    @Test
    fun `naturally presented events map to private spatial voice and exact lifecycle stops`() {
        RendererFixture().use { fixture ->
            val player = fixture.player(Pos.ZERO)
            val neighbor = fixture.player(Pos.ZERO)
            val binding =
                LobbyDialogueBinding(
                    LocalId("guide"),
                    Pos(-5.0, 142.0, 115.0),
                    LobbyGuideContent.DIALOGUE_ID,
                    "guide",
                )
            var now = 0L
            val listener =
                GuideSpeechListener(
                    player,
                    fixture.instance,
                    binding,
                    { true },
                    { true },
                    { error("reduced motion") },
                    testGuideVoiceBank(),
                    Duration.ofMillis(35),
                    { now },
                    { it.first },
                    { it.first },
                )
            val speaker =
                MinestomSpeakerBinding("test", fixture.instance) {
                    MinestomSpeakerBinding.AnchorSnapshot(
                        binding.position,
                        0f,
                        binding.position.add(0.0, 1.6, 0.0),
                        true,
                    )
                }
            fun event(
                node: String = "start",
                role: String = "guide",
                remaining: Int = 100,
                locale: String = "en",
            ) =
                MinestomDialogueSpeechEvent(
                    DialogueView(
                        UUID(0, 0),
                        ContentPin("0".repeat(64)),
                        node,
                        1,
                        DialogueView.State.REVEALING,
                        1,
                        1,
                        locale,
                        role,
                        "A".repeat(101),
                        emptyList(),
                        null,
                        101,
                        101 - remaining,
                        DialogueView.HintPhase.REVEAL,
                        "",
                        null,
                    ),
                    role,
                    speaker,
                    "a",
                )
            fun sounds() = player.connection.packets.filterIsInstance<SoundEffectPacket>()
            listener.onRevealed(event(role = "other"))
            listener.onRevealed(event(remaining = 1))
            assertTrue(sounds().isEmpty())
            val begin = event()
            listener.onBegin(
                MinestomDialogueSpeechBeginEvent(
                    begin.view(),
                    begin.speakerRole(),
                    begin.speaker(),
                    TypingTiming(
                        begin.view().sessionId(),
                        begin.view().nodeVisitId(),
                        begin.view().renderRevision(),
                        35_000_000L,
                        now - 35_000_000L,
                        now + 3_500_000_000L,
                    ),
                )
            )
            listener.onRevealed(event())
            val first = sounds().single()
            assertEquals("dialogue:guide/greeting/hello01", first.soundEvent().key().asString())
            assertEquals(Sound.Source.VOICE, first.source())
            assertEquals(-5.0, first.origin().x())
            assertEquals(143.6, first.origin().y())
            assertEquals(115.0, first.origin().z())
            listener.invalidate()
            listener.onBegin(
                MinestomDialogueSpeechBeginEvent(
                    begin.view(),
                    begin.speakerRole(),
                    begin.speaker(),
                    TypingTiming(
                        begin.view().sessionId(),
                        begin.view().nodeVisitId(),
                        begin.view().renderRevision(),
                        35_000_000L,
                        now - 35_000_000L,
                        now + 3_500_000_000L,
                    ),
                )
            )
            listener.onRevealed(begin)
            assertEquals(
                1,
                sounds().size,
                "An already queued begin cannot revive the invalidated locale",
            )
            listener.onStop(MinestomDialogueSpeechListener.StopReason.PAUSED)
            val stop = player.connection.packets.filterIsInstance<StopSoundPacket>().single()
            assertEquals(first.soundEvent().key().asString(), stop.sound())
            assertEquals(Sound.Source.VOICE, stop.source())
            now += 5_000_000_000
            val german = event(locale = "de")
            listener.onBegin(
                MinestomDialogueSpeechBeginEvent(
                    german.view(),
                    german.speakerRole(),
                    german.speaker(),
                    TypingTiming(
                        german.view().sessionId(),
                        german.view().nodeVisitId(),
                        german.view().renderRevision(),
                        35_000_000L,
                        now - 35_000_000L,
                        now + 3_500_000_000L,
                    ),
                )
            )
            listener.onRevealed(german)
            assertTrue(
                sounds().last().soundEvent().key().asString().startsWith("dialogue:guide/speech/")
            )
            listener.onStop(MinestomDialogueSpeechListener.StopReason.SESSION_ENDED)
            val count = sounds().size
            now += 5_000_000_000
            listener.onRevealed(event(node = "farewell"))
            assertEquals(count, sounds().size)
            assertTrue(
                neighbor.connection.packets.none {
                    it is SoundEffectPacket || it is StopSoundPacket
                }
            )
            listener.close()
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
