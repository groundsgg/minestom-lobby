package gg.grounds.minestom.lobby.scene

import gg.grounds.resourcepacks.npc.*
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class AnimatedNpcRendererTest {
    private fun model() =
        NpcModel.parse(
            """{"schemaVersion":1,"asset":"grounds:npc/test/worker","bounds":{"center":[0,1,0],"size":[1,2,1]},"bones":[{"id":"root","name":"root","parent":null,"origin":[0,0,0],"rotation":[0,0,0]}],"parts":[{"bone":"root","model":"grounds:npc_parts/test/worker/part_000","center":[0,1,0],"pivot":[0,1,0],"rotation":[0,0,0]}],"animations":{"idle":{"loop":"loop","length":2,"tracks":{}},"wave":{"loop":"once","length":1,"tracks":{"root":{"position":[{"time":0,"value":[0,0,0],"interpolation":"linear"},{"time":1,"value":[0,1,0],"interpolation":"linear"}]}}},"hidden":{"loop":"once","length":1,"tracks":{"root":{"scale":[{"time":0,"value":[0,0,0],"interpolation":"linear"}]}}}},"sounds":{}}"""
        )

    @Test
    fun `source animation moves the rendered part and a one-shot returns to idle`() {
        RendererFixture().use { fixture ->
            val model = model()
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
                                model.animations.keys.map(::LocalId).toSet(),
                                model.bounds,
                                emptyMap(),
                            )
                    ),
                )
            val registry = LobbyNpcModelRenderers(assets, mapOf(model.asset to model))
            val handle =
                registry
                    .rendererFor(model.asset, AssetKind.NPC_BODY)!!
                    .create(
                        SceneAssetRenderContext(
                            fixture.instance,
                            LocalId("npc"),
                            null,
                            model.asset,
                            sceneTransform(Vec3(10.0, 140.0, 20.0)),
                        )
                    )
                    .toCompletableFuture()
                    .join()
            val display =
                fixture.instance.entities.single { it.entityType == EntityType.ITEM_DISPLAY }
            val meta = display.entityMeta as ItemDisplayMeta
            assertEquals(
                "grounds:npc_parts/test/worker/part_000",
                meta.itemStack.get(DataComponents.ITEM_MODEL),
            )
            handle.startAnimation(LocalId("wave"), 0)
            handle.advanceAnimation(500)
            assertEquals(1.5, meta.translation.y(), 1e-6)
            handle.advanceAnimation(1500)
            assertEquals(1.0, meta.translation.y(), 1e-6)
            handle.startAnimation(LocalId("hidden"), 0)
            assertEquals(0.0, meta.scale.length(), 1e-6)
            assertTrue(meta.leftRotation.all { it.isFinite() })
            val player = fixture.player()
            handle.applyViewerState(player, SceneViewerVisualState(highlighted = true))
            assertTrue(player.connection.flags(display.entityId).last() and 0x40 != 0)
            handle.close()
            handle.advanceAnimation(2000)
            assertTrue(display.isRemoved)
            assertNull(registry.rendererFor(model.asset, AssetKind.PROP))
        }
    }

    @Test
    fun `unchanged parts do not restart old client interpolation`() {
        RendererFixture().use { fixture ->
            val model = model()
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
                                model.animations.keys.map(::LocalId).toSet(),
                                model.bounds,
                                emptyMap(),
                            )
                    ),
                )
            val player = fixture.player()
            val handle =
                LobbyNpcModelRenderers(assets, mapOf(model.asset to model))
                    .rendererFor(model.asset, AssetKind.NPC_BODY)!!
                    .create(
                        SceneAssetRenderContext(
                            fixture.instance,
                            LocalId("npc"),
                            null,
                            model.asset,
                            sceneTransform(),
                        )
                    )
                    .toCompletableFuture()
                    .join()
            handle.startAnimation(LocalId("idle"), 0)
            // Flush the entity's batched metadata before isolating steady-state updates.
            fixture.instance.tick(0)
            player.connection.packets.clear()
            for (time in listOf(100L, 200L, 300L)) {
                handle.advanceAnimation(time)
                fixture.instance.tick(time)
            }
            val updates =
                player.connection.packets.filterIsInstance<
                    net.minestom.server.network.packet.server.play.EntityMetaDataPacket
                >()
            assertTrue(updates.isEmpty(), "Static pose emitted repeated metadata: $updates")
            handle.close()
        }
    }

    @Test
    fun `clip audio is emitted once at start to nearby players and stops triggering after close`() {
        RendererFixture().use { fixture ->
            val key = AssetKey("grounds:npc/test/worker/wave")
            val model =
                model().copy(sounds = mapOf("wave" to NpcClipSound(key.value, 0.8, 1.0, 12.0)))
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
                                model.animations.keys.map(::LocalId).toSet(),
                                model.bounds,
                                emptyMap(),
                            ),
                        key to AssetDefinition(key, AssetKind.SOUND, emptySet(), null, emptyMap()),
                    ),
                )
            val near = fixture.player(net.minestom.server.coordinate.Pos(1.0, 1.0, 1.0))
            val distant = fixture.player(net.minestom.server.coordinate.Pos(30.0, 1.0, 30.0))
            val handle =
                LobbyNpcModelRenderers(assets, mapOf(model.asset to model))
                    .rendererFor(model.asset, AssetKind.NPC_BODY)!!
                    .create(
                        SceneAssetRenderContext(
                            fixture.instance,
                            LocalId("npc"),
                            null,
                            model.asset,
                            sceneTransform(),
                        )
                    )
                    .toCompletableFuture()
                    .join()
            fun sounds(player: TestPlayer) =
                player.connection.packets.filter { it.javaClass.simpleName == "SoundEffectPacket" }
            assertEquals(0, sounds(near).size)
            handle.startAnimation(LocalId("wave"), 0)
            assertEquals(1, sounds(near).size)
            assertEquals(0, sounds(distant).size)
            handle.advanceAnimation(500)
            handle.advanceAnimation(1500)
            assertEquals(1, sounds(near).size)
            handle.close()
            handle.startAnimation(LocalId("wave"), 0)
            assertEquals(1, sounds(near).size)
        }
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun boot() {
            MinecraftServer.init()
        }
    }
}
