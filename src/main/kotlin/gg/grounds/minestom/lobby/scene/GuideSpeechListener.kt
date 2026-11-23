package gg.grounds.minestom.lobby.scene

import gg.grounds.dialogue.minestom.*
import gg.grounds.npc.minestom.presentation.NpcPresentationStart
import gg.grounds.npc.minestom.spi.NpcPublicationGuard
import java.time.Duration
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance

internal class GuideSpeechListener(
    player: Player,
    instance: Instance,
    private val binding: LobbyDialogueBinding,
    valid: () -> Boolean,
    reducedMotion: () -> Boolean,
    prepare: (NpcPublicationGuard) -> NpcPresentationStart?,
    bank: GuideVoiceBank,
    graphemeDelay: Duration,
    clock: () -> Long = System::nanoTime,
    randomInt: (IntRange) -> Int = { it.random() },
    voiceRandomInt: (IntRange) -> Int = { it.random() },
) : MinestomDialogueSpeechListener, AutoCloseable {
    private val connection = player.playerConnection
    private val revealDelayNanos = graphemeDelay.toNanos()
    private val speech: GuideSpeechOutput

    init {
        fun sameConnection() = player.playerConnection === connection && player.isOnline
        speech =
            GuideSpeechOutput(
                { valid() && sameConnection() && player.instance === instance },
                { reducedMotion() },
                prepare,
                { voice ->
                    if (sameConnection())
                        player.playSound(
                            net.kyori.adventure.sound.Sound.sound(
                                net.kyori.adventure.key.Key.key(voice.key),
                                net.kyori.adventure.sound.Sound.Source.VOICE,
                                .4f * voice.volumeScale,
                                voice.pitch,
                            ),
                            binding.position.x(),
                            binding.position.y() + 1.6,
                            binding.position.z(),
                        )
                },
                { key ->
                    if (sameConnection())
                        player.stopSound(
                            net.kyori.adventure.sound.SoundStop.namedOnSource(
                                net.kyori.adventure.key.Key.key(key),
                                net.kyori.adventure.sound.Sound.Source.VOICE,
                            )
                        )
                },
                clock = clock,
                randomInt = randomInt,
                voiceRandomInt = voiceRandomInt,
                bank = bank,
            )
    }

    override fun onRevealed(event: MinestomDialogueSpeechEvent) {
        if (event.speakerRole() == binding.speaker)
            speech.reveal(
                event.revealed(),
                when (event.view().nodeId()) {
                    "start" -> GuideSpeechMood.GREETING
                    "farewell" -> GuideSpeechMood.FAREWELL
                    else -> null
                },
                // DialogueView carries the SDK's cached grapheme counts; never split text
                // per frame.
                (event.view().totalGraphemes() - event.view().revealedGraphemes())
                    .coerceAtLeast(0)
                    .toLong() * revealDelayNanos,
            )
    }

    override fun onStop(reason: MinestomDialogueSpeechListener.StopReason) {
        when (reason) {
            MinestomDialogueSpeechListener.StopReason.PRESENTATION_FALLBACK -> speech.fallback()
            MinestomDialogueSpeechListener.StopReason.PAUSED,
            MinestomDialogueSpeechListener.StopReason.CONTENT_REPLACED -> speech.stop(false)
            else -> speech.close()
        }
    }

    override fun close() = speech.close()
}
