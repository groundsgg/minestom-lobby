package gg.grounds.minestom.lobby.scene

import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition.*
import gg.grounds.dialogue.core.runtime.DialogueStartResult
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.runtime.RuntimeOptions
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.core.text.TextCatalog
import gg.grounds.dialogue.core.text.TextResolver
import gg.grounds.dialogue.core.validation.DialogueValidator
import gg.grounds.dialogue.core.validation.HostCatalog
import gg.grounds.dialogue.core.validation.ValidationLimits
import gg.grounds.dialogue.minestom.*
import gg.grounds.dialogue.minestom.asset.AssetBundle
import gg.grounds.dialogue.minestom.asset.MinecraftPackProfile
import gg.grounds.dialogue.minestom.channel.LocalOutputChannels
import gg.grounds.dialogue.minestom.input.InputProfile
import gg.grounds.dialogue.presentation.UiLabelSource
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.SceneHand
import gg.grounds.scene.format.SceneTrigger
import gg.grounds.scene.minestom.SceneActionContext
import gg.grounds.scene.minestom.SceneActionResult
import gg.grounds.scene.minestom.SceneRuntimeIdentity
import java.time.Duration
import java.util.Locale
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance

/**
 * Stage-only, chat-only exercise of the real dialogue runtime. No gameplay actions are installed.
 */
internal class StageLobbyDialogue(
    private val instance: Instance,
    private val identity: SceneRuntimeIdentity,
    private val speakerElementId: LocalId,
    private val speakerPosition: Pos,
    private val locale: (Player) -> String,
) : LobbySceneDialogueStarter {
    private val lock = Any()
    @Volatile private var closed = false
    private val sessions = WeakHashMap<Player, MinestomDialogueStart>()
    private val texts =
        TextCatalog(
            mapOf(
                "en" to
                    mapOf(
                        "welcome" to
                            "Hello! This is the Stage dialogue test. What would you like to know?",
                        "about.choice" to "What can I test here?",
                        "leave.choice" to "Goodbye!",
                        "about" to
                            "These clickable chat choices run through the dialogue library. This test does not change your game progress.",
                        "bye" to
                            "Thanks for testing. You can talk to me again after this conversation ends.",
                    ),
                "de" to
                    mapOf(
                        "welcome" to "Hallo! Das ist der Stage-Dialogtest. Was möchtest du wissen?",
                        "about.choice" to "Was kann ich hier testen?",
                        "leave.choice" to "Auf Wiedersehen!",
                        "about" to
                            "Diese anklickbaren Chat-Antworten laufen durch die Dialogbibliothek. Dieser Test verändert deinen Spielfortschritt nicht.",
                        "bye" to
                            "Danke fürs Testen. Nach diesem Gespräch kannst du mich wieder ansprechen.",
                    ),
            )
        )
    private val generation = "stage-dialogue-" + UUID.randomUUID()
    private val content =
        checkNotNull(
            DialogueValidator()
                .validate(
                    DialogueBuilder.dialogue(DIALOGUE_ID)
                        .locales("en", "en", "de")
                        .interaction(Interaction(LookAway(false, 110.0, 600, 95.0), 6.0))
                        .node(
                            "start",
                            Message(
                                SPEAKER_ROLE,
                                "welcome",
                                null,
                                listOf(
                                    Choice("about", "about.choice", "about", false, null),
                                    Choice("leave", "leave.choice", "bye", false, null),
                                ),
                                null,
                            ),
                        )
                        .node("about", Message(SPEAKER_ROLE, "about", null, emptyList(), "bye"))
                        .node("bye", Message(SPEAKER_ROLE, "bye", null, emptyList(), "end"))
                        .node("end", End("completed"))
                        .build(),
                    texts,
                    HostCatalog(
                        generation,
                        setOf(SPEAKER_ROLE),
                        emptyMap(),
                        emptyMap(),
                        emptySet(),
                        emptySet(),
                        false,
                    ),
                    ValidationLimits.defaults(),
                )
                .validated()
        ) {
            "Stage dialogue content validation failed"
        }
    private val services =
        DialogueServices(
            generation,
            emptyMap(),
            emptyMap(),
            null,
            TextResolver.catalogue(texts, "en"),
            emptySet(),
        )
    private val host =
        MinestomDialogueHost(
            MinestomDialogueOptions(
                RuntimeOptions.Budgets(
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(30),
                ),
                MinecraftPackProfile.grounds263(),
                AssetBundle.loadDefault(MinecraftPackProfile.grounds263()),
                // Semantic chat never acquires HUD/effect layers. Keep these private sinks inert.
                LocalOutputChannels(
                    { _, _ -> CompletableFuture.completedFuture(null) },
                    false,
                    { _, _ -> CompletableFuture.completedFuture(null) },
                ),
                UiLabelSource.defaults(),
                Duration.ofMillis(600),
            )
        )

    override fun start(
        context: SceneActionContext,
        dialogue: String,
        speaker: String,
    ): CompletionStage<SceneActionResult> {
        synchronized(lock) {
            if (
                closed ||
                    context.identity != identity ||
                    context.elementId != speakerElementId ||
                    context.player.instance !== instance ||
                    !context.player.isOnline ||
                    context.trigger != SceneTrigger.RIGHT_CLICK ||
                    context.hand != SceneHand.MAIN ||
                    dialogue != DIALOGUE_ID ||
                    speaker != SPEAKER_ROLE
            )
                return rejected("Stage dialogue binding rejected")
            val previous = sessions[context.player]
            // Retain the admission until the library confirms presentation cleanup, including
            // failures.
            if (previous != null && previous.status().diagnostic != "CLOSED") {
                return rejected("A dialogue is already active")
            }
            val language = Locale.forLanguageTag(locale(context.player).replace('_', '-')).language
            val ticket =
                host.begin(
                    MinestomDialogueRequest(
                        UUID.randomUUID().toString(),
                        context.player,
                        0,
                        content,
                        services,
                        emptyMap(),
                        LocaleContext(if (language == "de") "de" else "en", 0, "en"),
                        PresentationPreferences("chat", Duration.ZERO, true, emptyMap()),
                        mapOf(
                            SPEAKER_ROLE to
                                MinestomSpeakerBinding(
                                    generation,
                                    instance,
                                    {
                                        MinestomSpeakerBinding.AnchorSnapshot(
                                            speakerPosition,
                                            speakerPosition.yaw(),
                                            speakerPosition.add(0.0, 1.6, 0.0),
                                            !closed,
                                        )
                                    },
                                )
                        ),
                        null,
                        InputProfile.defaults(),
                        0,
                    )
                )
            sessions[context.player] = ticket
            return ticket.result().thenApply { result ->
                when (result) {
                    is DialogueStartResult.Started -> SceneActionResult.Success
                    is DialogueStartResult.Rejected ->
                        SceneActionResult.Rejected("Dialogue start rejected: ${result.code()}")
                }
            }
        }
    }

    fun closeAsync(): CompletionStage<Void> =
        synchronized(lock) {
            closed = true
            sessions.clear()
            host.closeAsync()
        }

    private fun rejected(reason: String): CompletionStage<SceneActionResult> =
        CompletableFuture.completedFuture(SceneActionResult.Rejected(reason))

    companion object {
        const val DIALOGUE_ID = "stage:mechanic"
        const val SPEAKER_ROLE = "guide"
    }
}
