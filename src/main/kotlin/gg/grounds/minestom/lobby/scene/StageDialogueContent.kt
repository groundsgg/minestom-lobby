package gg.grounds.minestom.lobby.scene

import gg.grounds.dialogue.core.validation.HostCatalog

/** Stage's packaged demonstration content; no action providers are authorized. */
internal object StageDialogueContent {
    const val DIALOGUE_ID = "stage:mechanic"
    const val SPEAKER_ROLE = "guide"

    private val content by lazy {
        BundledDialogueContent.load(
            "stage-mechanic",
            DIALOGUE_ID,
            HostCatalog(
                "bundled-content",
                setOf(SPEAKER_ROLE),
                emptyMap(),
                emptyMap(),
                emptySet(),
                emptySet(),
                false,
            ),
        )
    }

    val texts
        get() = content.texts

    fun validated(
        generation: String,
        registration: LobbyDialogueHostRegistration = LobbyDialogueHostRegistration.create(DIALOGUE_ID),
    ): gg.grounds.dialogue.core.validation.ValidatedDialogue {
        require(registration.dialogueId == DIALOGUE_ID)
        return content.validated(registration.catalog(generation))
    }
}
