package gg.grounds.minestom.lobby.scene

import gg.grounds.dialogue.core.validation.ValidatedDialogue
import gg.grounds.dialogue.minestom.asset.AssetBundle
import gg.grounds.dialogue.presentation.DialogueTheme
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.minestom.SceneActionContext
import gg.grounds.scene.minestom.SceneRuntimeIdentity
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance

/** Compatibility facade for the explicit Stage test station. */
internal class StageLobbyDialogue(
    instance: Instance,
    identity: SceneRuntimeIdentity,
    speakerElementId: LocalId,
    speakerPosition: Pos,
    assets: AssetBundle = StageDialogueAssets.load(),
    proxyPackEnabled: Boolean = StageDialogueAssets.isPackaged(),
    theme: DialogueTheme = LobbyDialogues.defaultTheme(),
    content: ((String, String) -> ValidatedDialogue)? = null,
    locale: (Player) -> String,
) : LobbySceneDialogueStarter {
    private val owner =
        LobbyDialogues(
            instance,
            identity,
            listOf(
                LobbyDialogueBinding(speakerElementId, speakerPosition, DIALOGUE_ID, SPEAKER_ROLE)
            ),
            assets = assets,
            proxyPackEnabled = proxyPackEnabled,
            theme = theme,
            locale = locale,
            content = content,
        )

    override fun start(context: SceneActionContext, dialogue: String, speaker: String) =
        owner.start(context, dialogue, speaker)

    fun confirmPack(player: Player, state: DialoguePackReadiness.State) =
        owner.confirmPack(player, state)

    internal fun presentation(player: Player) = owner.presentation(player)

    internal fun npcDialogueViewers() = owner.npcDialogueViewers()

    fun closeAsync() = owner.closeAsync()

    companion object {
        const val DIALOGUE_ID = "stage:mechanic"
        const val SPEAKER_ROLE = "guide"
    }
}
