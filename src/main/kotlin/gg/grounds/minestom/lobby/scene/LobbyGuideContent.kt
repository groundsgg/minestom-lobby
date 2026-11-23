package gg.grounds.minestom.lobby.scene

import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition.*
import gg.grounds.dialogue.core.text.TextCatalog
import gg.grounds.dialogue.core.validation.DialogueValidator
import gg.grounds.dialogue.core.validation.HostCatalog
import gg.grounds.dialogue.core.validation.ValidatedDialogue
import gg.grounds.dialogue.core.validation.ValidationLimits
import gg.grounds.lobby.scene.LobbySceneCatalogs

/** Bilingual conversation content for the persistent Lobby Guide. */
internal object LobbyGuideContent {
    const val DIALOGUE_ID = "grounds:lobby-guide"
    const val SPEAKER_ROLE = "guide"

    val NAVIGATOR_ACTION = LobbySceneCatalogs.OPEN_NAVIGATOR.value
    val DUELS_ACTION = LobbySceneCatalogs.OPEN_DUEL_MENU.value

    val texts =
        TextCatalog(
            mapOf(
                "en" to
                    mapOf(
                        "welcome" to "Hi! I’m your guide. What would you like to do?",
                        "games.choice" to "Find a game",
                        "travel.choice" to "Choose a lobby or region",
                        "orientation.choice" to "How do I get around?",
                        "goodbye.choice" to "Goodbye",
                        "games" to
                            "Use the compass in your first hotbar slot to browse available games. You can choose a mode there.",
                        "duels.choice" to "Open the duel menu",
                        "travel" to "The navigator lets you choose another lobby or region.",
                        "navigator.choice" to "Open the navigator",
                        "orientation" to "Use /spawn to return to the lobby spawn.",
                        "back.choice" to "Back",
                        "unavailable" to
                            "That menu isn’t available right now. Please try again later.",
                        "farewell" to "See you around!",
                    ),
                "de" to
                    mapOf(
                        "welcome" to "Hallo! Ich bin dein Guide. Was möchtest du tun?",
                        "games.choice" to "Ein Spiel finden",
                        "travel.choice" to "Lobby oder Region wählen",
                        "orientation.choice" to "Wie finde ich mich zurecht?",
                        "goodbye.choice" to "Auf Wiedersehen",
                        "games" to
                            "Mit dem Kompass im ersten Hotbar-Slot kannst du die verfügbaren Spiele ansehen und einen Modus auswählen.",
                        "duels.choice" to "Duellmenü öffnen",
                        "travel" to "Im Navigator kannst du eine andere Lobby oder Region wählen.",
                        "navigator.choice" to "Navigator öffnen",
                        "orientation" to "Mit /spawn kehrst du zum Lobby-Spawn zurück.",
                        "back.choice" to "Zurück",
                        "unavailable" to
                            "Dieses Menü ist gerade nicht verfügbar. Bitte versuche es später noch einmal.",
                        "farewell" to "Bis bald!",
                    ),
            )
        )

    fun validated(generation: String): ValidatedDialogue {
        val actions =
            mapOf(
                NAVIGATOR_ACTION to HostCatalog.ProviderSpec(emptyMap(), false),
                DUELS_ACTION to HostCatalog.ProviderSpec(emptyMap(), false),
            )
        val host =
            HostCatalog(
                generation,
                setOf(SPEAKER_ROLE),
                emptyMap(),
                actions,
                emptySet(),
                emptySet(),
                false,
            )
        val definition =
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
                            Choice("games", "games.choice", "games", false, null),
                            Choice("travel", "travel.choice", "travel", false, null),
                            Choice("orientation", "orientation.choice", "orientation", false, null),
                            Choice("goodbye", "goodbye.choice", "farewell", false, null),
                        ),
                        null,
                    ),
                )
                .node(
                    "games",
                    Message(
                        SPEAKER_ROLE,
                        "games",
                        null,
                        listOf(
                            Choice("duels", "duels.choice", "duels_action", false, null),
                            backChoice(),
                            goodbyeChoice(),
                        ),
                        null,
                    ),
                )
                .node(
                    "duels_action",
                    Action(
                        DUELS_ACTION,
                        emptyMap(),
                        "end",
                        "menu_unavailable",
                        "menu_unavailable",
                        "menu_unavailable",
                    ),
                )
                .node(
                    "travel",
                    Message(
                        SPEAKER_ROLE,
                        "travel",
                        null,
                        listOf(
                            Choice(
                                "navigator",
                                "navigator.choice",
                                "navigator_action",
                                false,
                                null,
                            ),
                            backChoice(),
                            goodbyeChoice(),
                        ),
                        null,
                    ),
                )
                .node(
                    "navigator_action",
                    Action(
                        NAVIGATOR_ACTION,
                        emptyMap(),
                        "end",
                        "menu_unavailable",
                        "menu_unavailable",
                        "menu_unavailable",
                    ),
                )
                .node(
                    "orientation",
                    Message(
                        SPEAKER_ROLE,
                        "orientation",
                        null,
                        listOf(backChoice(), goodbyeChoice()),
                        null,
                    ),
                )
                .node(
                    "menu_unavailable",
                    Message(
                        SPEAKER_ROLE,
                        "unavailable",
                        null,
                        listOf(backChoice(), goodbyeChoice()),
                        null,
                    ),
                )
                .node("farewell", Message(SPEAKER_ROLE, "farewell", null, emptyList(), "end"))
                .node("end", End("completed"))
                .build()

        return checkNotNull(
            DialogueValidator()
                .validate(definition, texts, host, ValidationLimits.defaults())
                .validated()
        ) {
            "Lobby Guide content validation failed"
        }
    }

    private fun backChoice() = Choice("back", "back.choice", "start", false, null)

    private fun goodbyeChoice() = Choice("goodbye", "goodbye.choice", "farewell", false, null)
}
