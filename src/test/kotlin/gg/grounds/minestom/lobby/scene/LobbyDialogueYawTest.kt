package gg.grounds.minestom.lobby.scene

import gg.grounds.dialogue.minestom.MinestomSpeakerBinding
import gg.grounds.dialogue.minestom.geometry.NpcPanelTransform
import gg.grounds.lobby.scene.LobbySceneCatalogs
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import net.minestom.server.coordinate.Vec
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LobbyDialogueYawTest {
    @Test
    fun `dialogue panel follows the authored NPC front for positive and negative Scene yaw`() {
        for ((yaw, front) in
            listOf(
                90.0 to Vec(1.0, 0.0, 0.0),
                -90.0 to Vec(-1.0, 0.0, 0.0),
                35.0 to Vec(.573576436, 0.0, .819152044),
                -35.0 to Vec(-.573576436, 0.0, .819152044),
            )) {
            val prepared = scene(yaw)
            val original = prepared.scene.elements.single() as Npc
            val binding = prepared.dialogueBindings().single()
            val anchor =
                MinestomSpeakerBinding.AnchorSnapshot(
                    binding.position,
                    binding.position.yaw(),
                    binding.position.add(0.0, 1.6, 0.0),
                    true,
                )
            val panel =
                NpcPanelTransform.from(
                    anchor,
                    binding.position.add(front.mul(4.0)).add(0.0, 1.9, 0.0),
                )
            assertEquals(front.x(), panel.normal.x(), .000001, "Scene yaw=$yaw")
            assertEquals(front.z(), panel.normal.z(), .000001, "Scene yaw=$yaw")
            assertEquals(-yaw.toFloat(), binding.position.yaw())
            assertEquals(3.0, binding.position.x())
            assertEquals(142.0, binding.position.y())
            assertEquals(110.0, binding.position.z())
            assertSame(original, prepared.scene.elements.single())
            assertEquals(yaw, original.transform.rotation.yaw)
            assertEquals(Vec3(.5, 2.0, 1.0), original.labelOffset)
        }
    }

    private fun scene(yaw: Double): PreparedLobbyScene {
        val assets = GroundsAssetCatalog.catalog
        val actions = LobbySceneCatalogs.DIALOGUE
        val npc =
            Npc(
                LocalId("spawn-guide"),
                null,
                Transform(
                    Vec3(3.0, 142.0, 110.0),
                    EulerRotation(yaw, 0.0, 0.0),
                    Vec3(1.0, 1.0, 1.0),
                ),
                true,
                ActivationPolicy.AUTOMATIC,
                AssetKey("grounds:editor/guide"),
                null,
                Vec3(.5, 2.0, 1.0),
                LookBehavior.Fixed,
                null,
                unitBounds,
                null,
                listOf(
                    TriggerBinding(
                        SceneTrigger.RIGHT_CLICK,
                        listOf(HandCondition(SceneHand.MAIN)),
                        0,
                        0,
                        listOf(
                            ApplicationAction(
                                LobbySceneCatalogs.START_DIALOGUE,
                                mapOf(
                                    LobbySceneCatalogs.DIALOGUE_ID to
                                        StringArgument(LobbyGuideContent.DIALOGUE_ID),
                                    LobbySceneCatalogs.SPEAKER to
                                        StringArgument(LobbyGuideContent.SPEAKER_ROLE),
                                ),
                            )
                        ),
                    )
                ),
                NpcCollision(),
            )
        val document =
            SceneDocument(
                1,
                SceneId("test:dialogue-yaw"),
                SceneMetadata("Yaw", null, emptySet()),
                SceneCatalogReferences(
                    CatalogReference(assets.id, assets.version),
                    CatalogReference(actions.id, actions.version),
                ),
                emptyList(),
                listOf(npc),
            )
        return PreparedLobbyScene(
            document,
            assets,
            actions,
            SceneRuntimeIdentity(document.id, "lobby/mainlobby", 19),
            SceneAssetRendererRegistry { _, _ -> null },
        )
    }
}
