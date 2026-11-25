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
) : MinestomDialogueTimedSpeechListener, AutoCloseable {
    private val connection = player.playerConnection
    private val lock = Any()

    private data class Phase(
        val session: java.util.UUID,
        val visit: Long,
        val revision: Long,
        val locale: String,
    )

    private var phase: Phase? = null
    private var expectedLocale: String? = null
    private var invalidatedPhase: Phase? = null

    private fun identity(view: gg.grounds.dialogue.core.runtime.DialogueView) =
        Phase(view.sessionId(), view.nodeVisitId(), view.renderRevision(), view.localeTag())

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

    override fun onBegin(event: MinestomDialogueSpeechBeginEvent) =
        synchronized(lock) {
            val view = event.view()
            val timing = event.timing()
            if (
                event.speakerRole() != binding.speaker ||
                    view.state() != gg.grounds.dialogue.core.runtime.DialogueView.State.REVEALING ||
                    view.revealedGraphemes() <= 0 ||
                    timing.sessionId() != view.sessionId() ||
                    timing.nodeVisitId() != view.nodeVisitId() ||
                    timing.renderRevision() != view.renderRevision()
            )
                return@synchronized
            val identity = identity(view)
            if (
                phase == identity ||
                    invalidatedPhase == identity ||
                    expectedLocale?.let { it != view.localeTag() } == true
            )
                return@synchronized
            phase = identity
            speech.beginSpeech(
                GuideSpeechContext(
                    view.text(),
                    view.localeTag(),
                    view.nodeId(),
                    view.totalGraphemes(),
                    view.revealedGraphemes(),
                    timing,
                ),
                when (view.nodeId()) {
                    "start" -> GuideSpeechMood.GREETING
                    "farewell" -> GuideSpeechMood.FAREWELL
                    else -> null
                },
            )
            speech.reveal(view.text(), view.revealedGraphemes())
        }

    override fun onRevealed(event: MinestomDialogueSpeechEvent) =
        synchronized(lock) {
            if (
                event.speakerRole() == binding.speaker &&
                    phase == identity(event.view()) &&
                    event.view().state() ==
                        gg.grounds.dialogue.core.runtime.DialogueView.State.REVEALING
            )
                speech.reveal(event.revealed(), event.view().revealedGraphemes())
        }

    fun invalidate(locale: String? = null) =
        synchronized(lock) {
            invalidatedPhase = phase
            expectedLocale = locale
            phase = null
            speech.stop(false)
        }

    override fun onStop(reason: MinestomDialogueSpeechListener.StopReason) =
        synchronized(lock) {
            invalidatedPhase = phase ?: invalidatedPhase
            phase = null
            when (reason) {
                MinestomDialogueSpeechListener.StopReason.PRESENTATION_FALLBACK -> speech.fallback()
                MinestomDialogueSpeechListener.StopReason.PAUSED,
                MinestomDialogueSpeechListener.StopReason.CONTENT_REPLACED -> speech.stop(false)
                else -> speech.close()
            }
        }

    override fun close() =
        synchronized(lock) {
            phase = null
            speech.close()
        }
}
