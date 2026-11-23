package gg.grounds.minestom.lobby.scene

import gg.grounds.lobby.scene.LobbySceneCatalogs
import gg.grounds.scene.format.*
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec

internal fun stageDialoguePreviewEnabled(environment: Map<String, String>): Boolean {
    if (environment["GROUNDS_DIALOGUE_STAGE_DEMO_ENABLED"] != "true") return false
    require(environment["GROUNDS_PERMISSION_ENVIRONMENT"] == "stage") {
        "Dialogue preview may only activate on Stage"
    }
    return true
}

internal data class StageDialoguePreview(
    val prepared: PreparedLobbyScene,
    val stationId: LocalId,
    val position: Pos,
    val label: StageDialogueLabel,
    val body: AssetKey,
)

/** Adds a test interaction to the already verified Stage map for this process. */
internal fun PreparedLobbyScene.withStageDialoguePreview(): StageDialoguePreview {
    val station = scene.elements.filterIsInstance<Npc>().single { it.id.value == "demo-bounce" }
    val action =
        ApplicationAction(
            LobbySceneCatalogs.START_DIALOGUE,
            mapOf(
                LobbySceneCatalogs.DIALOGUE_ID to StringArgument(StageLobbyDialogue.DIALOGUE_ID),
                LobbySceneCatalogs.SPEAKER to StringArgument(StageLobbyDialogue.SPEAKER_ROLE),
            ),
        )
    val binding =
        TriggerBinding(
            SceneTrigger.RIGHT_CLICK,
            listOf(HandCondition(SceneHand.MAIN), SneakingCondition(true)),
            1000,
            250,
            listOf(action),
        )
    val actions = LobbySceneCatalogs.DIALOGUE
    val preview =
        SceneDocument(
            scene.schemaVersion,
            scene.id,
            scene.metadata,
            scene.catalogs.copy(actions = CatalogReference(actions.id, actions.version)),
            scene.groups,
            scene.elements.map { element ->
                if (element !== station) element
                else
                    Npc(
                        station.id,
                        station.group,
                        station.transform,
                        station.visible,
                        station.activation,
                        station.body,
                        station.label,
                        station.labelOffset,
                        station.look,
                        station.initialAnimation,
                        station.interactionBounds,
                        station.proximity,
                        station.bindings + binding,
                        station.collision,
                    )
            },
        )
    check(SceneValidation.validateCatalogs(preview, assets, actions).problems.isEmpty()) {
        "Invalid Stage dialogue preview catalog"
    }
    val origin = station.transform.position
    val transform = station.transform
    val offset = station.labelOffset
    val rotation = transform.rotation
    val scaledOffset =
        Vec(
            offset.x * transform.scale.x,
            offset.y * transform.scale.y,
            offset.z * transform.scale.z,
        )
    val rotatedOffset =
        scaledOffset
            .rotateAroundZ(Math.toRadians(rotation.roll))
            .rotateAroundX(Math.toRadians(rotation.pitch))
            .rotateAroundY(Math.toRadians(rotation.yaw))
    val position = Pos(origin.x, origin.y, origin.z)
    return StageDialoguePreview(
        copy(scene = preview, actions = actions),
        station.id,
        position,
        StageDialogueLabel(station.id, requireNotNull(station.label), position.add(rotatedOffset)),
        station.body,
    )
}
