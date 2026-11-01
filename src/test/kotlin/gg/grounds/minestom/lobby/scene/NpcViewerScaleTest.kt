package gg.grounds.minestom.lobby.scene

import gg.grounds.resourcepacks.npc.NpcModel
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Point
import net.minestom.server.entity.MetadataDef
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NpcViewerScaleTest {
    @Test
    fun `personal scale survives animation transforms and retracking without affecting another viewer`() {
        MinecraftServer.init()
        val model =
            NpcModel.parse(
                """{"schemaVersion":1,"asset":"grounds:npc/test/scaling","bounds":{"center":[0,1,0],"size":[1,2,1]},"bones":[{"id":"root","name":"root","parent":null,"origin":[0,0,0],"rotation":[0,0,0]}],"parts":[{"bone":"root","model":"grounds:npc_parts/test/scaling/part_000","center":[0,1,0],"pivot":[0,1,0],"rotation":[0,0,0]}],"animations":{"wave":{"loop":"once","length":2,"tracks":{"root":{"position":[{"time":0,"value":[0,0,0],"interpolation":"linear"},{"time":2,"value":[0,2,0],"interpolation":"linear"}]}}}},"sounds":{}}"""
            )
        val assets =
            AssetCatalog(
                CatalogId("grounds:assets"),
                "1.0.0",
                CatalogVersionRange(CatalogId("grounds:pack"), "1.0.0", "1.0.0"),
                mapOf(
                    model.asset to
                        AssetDefinition(
                            model.asset,
                            AssetKind.NPC_BODY,
                            setOf(LocalId("wave")),
                            model.bounds,
                            emptyMap(),
                        )
                ),
            )
        RendererFixture().use { fixture ->
            val first = fixture.player()
            val second = fixture.player()
            val handle =
                LobbyNpcModelRenderers(assets, mapOf(model.asset to model))
                    .rendererFor(model.asset, AssetKind.NPC_BODY)!!
                    .create(
                        SceneAssetRenderContext(
                            fixture.instance,
                            LocalId("scaled"),
                            null,
                            model.asset,
                            sceneTransform(),
                        )
                    )
                    .toCompletableFuture()
                    .join()
            val display = fixture.instance.entities.filterIsInstance<NpcItemDisplay>().single()
            display.addViewer(first)
            display.addViewer(second)
            fun value(player: TestPlayer, field: Int): Point =
                player.connection.packets
                    .filterIsInstance<EntityMetaDataPacket>()
                    .filter { it.entityId() == display.entityId }
                    .mapNotNull { it.entries()[field]?.value() as? Point }
                    .last()
            val translation = MetadataDef.Display.TRANSLATION.index()
            val scale = MetadataDef.Display.SCALE.index()
            handle.startAnimation(LocalId("wave"), 0)
            handle.applyViewerState(first, SceneViewerVisualState(2.0, true))
            handle.advanceAnimation(500)
            fixture.instance.tick(500)
            assertEquals(3.0, value(first, translation).y(), 1e-6)
            assertEquals(1.5, value(second, translation).y(), 1e-6)
            assertEquals(2.0, value(first, scale).x(), 1e-6)
            assertEquals(1.0, value(second, scale).x(), 1e-6)
            assertEquals(1.5, (display.entityMeta as ItemDisplayMeta).translation.y(), 1e-6)
            handle.applyTransform(sceneTransform(Vec3(10.0, 140.0, 20.0)))
            handle.advanceAnimation(1000)
            fixture.instance.tick(1000)
            assertEquals(4.0, value(first, translation).y(), 1e-6)
            assertEquals(2.0, value(second, translation).y(), 1e-6)
            display.removeViewer(first)
            display.addViewer(first)
            assertEquals(4.0, value(first, translation).y(), 1e-6)
            assertTrue(first.connection.flags(display.entityId).last() and 0x40 != 0)
            handle.clearViewerState(first)
            assertEquals(2.0, value(first, translation).y(), 1e-6)
            assertEquals(1.0, value(first, scale).x(), 1e-6)
            handle.close()
            assertTrue(display.isRemoved)
        }
    }
}
