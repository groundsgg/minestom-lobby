package gg.grounds.minestom.lobby.scene

import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition.*
import gg.grounds.dialogue.core.text.TextCatalog
import gg.grounds.dialogue.core.validation.DialogueValidator
import gg.grounds.dialogue.core.validation.HostCatalog
import gg.grounds.dialogue.core.validation.ValidationLimits
import gg.grounds.dialogue.minestom.*

internal object StageDialogueContent {
    const val DIALOGUE_ID = "stage:mechanic"
    const val SPEAKER_ROLE = "guide"
    val texts =
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

    fun validated(generation: String) =
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
}
