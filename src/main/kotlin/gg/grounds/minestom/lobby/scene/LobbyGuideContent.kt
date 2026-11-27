package gg.grounds.minestom.lobby.scene

import gg.grounds.dialogue.core.validation.HostCatalog
import gg.grounds.lobby.scene.LobbySceneCatalogs

/** The persistent Guide's packaged content and host-owned menu contract. */
internal object LobbyGuideContent {
    const val DIALOGUE_ID = "grounds:lobby-guide"
    const val SPEAKER_ROLE = "guide"

    val NAVIGATOR_ACTION = LobbySceneCatalogs.OPEN_NAVIGATOR.value
    val DUELS_ACTION = LobbySceneCatalogs.OPEN_DUEL_MENU.value

    private val content by lazy {
        BundledDialogueContent.load(
            "lobby-guide",
            DIALOGUE_ID,
            HostCatalog(
                "bundled-content",
                setOf(SPEAKER_ROLE),
                emptyMap(),
                mapOf(
                    NAVIGATOR_ACTION to HostCatalog.ProviderSpec(emptyMap(), false),
                    DUELS_ACTION to HostCatalog.ProviderSpec(emptyMap(), false),
                ),
                emptySet(),
                emptySet(),
                false,
            ),
        )
    }

    val texts
        get() = content.texts

    fun validated(generation: String) = content.validated(generation)
}
