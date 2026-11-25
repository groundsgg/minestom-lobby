package gg.grounds.minestom.lobby.scene

/** Finite operator-selected capabilities; Story IDs never select providers. */
internal enum class LobbyDialogueHostContract(
    val profileId: String,
    val builtInDialogueId: String,
) {
    GUIDE("grounds:lobby-guide", LobbyGuideContent.DIALOGUE_ID),
    STAGE("grounds:stage-dialogue", StageDialogueContent.DIALOGUE_ID);

    companion object {
        fun fromProfileId(id: String): LobbyDialogueHostContract =
            entries.singleOrNull { it.profileId == id }
                ?: throw IllegalArgumentException("Unsupported lobby host contract $id")

        fun forBuiltIn(dialogueId: String): LobbyDialogueHostContract =
            entries.singleOrNull { it.builtInDialogueId == dialogueId }
                ?: throw IllegalArgumentException("Unsupported lobby dialogue $dialogueId")
    }
}
