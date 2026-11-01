package gg.grounds.minestom.lobby.scene

import gg.grounds.resourcepacks.npc.*
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.MetadataDef
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import net.minestom.server.event.entity.EntityTeleportEvent
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class NpcRenderPerformanceTest {
    private val model =
        NpcModel.parse(
            """{"schemaVersion":1,"asset":"grounds:npc/test/performance","bounds":{"center":[0,1,0],"size":[1,2,1]},"bones":[{"id":"body","name":"body","parent":null,"origin":[0,0,0],"rotation":[0,0,0]},{"id":"head","name":"head","parent":"body","origin":[0,1.5,0],"rotation":[0,0,0]}],"parts":[{"bone":"body","model":"grounds:npc_parts/test/body","center":[0,0.7,0],"pivot":[0,0.7,0],"rotation":[0,0,0]},{"bone":"head","model":"grounds:npc_parts/test/head","center":[0,1.8,0],"pivot":[0,1.8,0],"rotation":[0,0,0]}],"animations":{"idle":{"loop":"loop","length":2,"tracks":{}},"wave":{"loop":"once","length":2,"tracks":{"body":{"position":[{"time":0,"value":[0,0,0],"interpolation":"linear"},{"time":2,"value":[0,2,0],"interpolation":"linear"}]}}}},"sounds":{}}"""
        )

    private fun create(fixture: RendererFixture): HeadLookRenderedAssetHandle {
        val assets =
            AssetCatalog(
                CatalogId("test:assets"),
                "1",
                CatalogVersionRange(CatalogId("test:pack"), "1", "1"),
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
        return LobbyNpcModelRenderers(assets, mapOf(model.asset to model))
            .rendererFor(model.asset, AssetKind.NPC_BODY)!!
            .create(
                SceneAssetRenderContext(
                    fixture.instance,
                    LocalId("performance"),
                    null,
                    model.asset,
                    sceneTransform(),
                )
            )
            .toCompletableFuture()
            .join() as HeadLookRenderedAssetHandle
    }

    @Test
    fun `translation animation does not resend unchanged rotations`() {
        RendererFixture().use { fixture ->
            val player = fixture.player()
            val handle = create(fixture)
            fixture.instance.entities.filterIsInstance<NpcItemDisplay>().forEach {
                it.addViewer(player)
            }
            handle.startAnimation(LocalId("wave"), 0)
            player.connection.packets.clear()
            handle.advanceAnimation(500)
            val updates = player.connection.packets.filterIsInstance<EntityMetaDataPacket>()
            assertEquals(2, updates.size)
            updates.forEach {
                assertTrue(
                    setOf(
                            MetadataDef.Display.TRANSLATION.index(),
                            MetadataDef.Display.INTERPOLATION_DELAY.index(),
                            MetadataDef.Display.TRANSFORMATION_INTERPOLATION_DURATION.index(),
                        )
                        .containsAll(it.entries().keys)
                )
                assertTrue(MetadataDef.Display.TRANSLATION.index() in it.entries())
                assertTrue(MetadataDef.Display.INTERPOLATION_DELAY.index() in it.entries())
            }
            handle.close()
        }
    }

    @Test
    fun `body yaw never teleports stationary display anchors`() {
        RendererFixture().use { fixture ->
            val handle = create(fixture)
            var teleports = 0
            fixture.instance.entities.filterIsInstance<NpcItemDisplay>().forEach { display ->
                display.eventNode().addListener(EntityTeleportEvent::class.java) { teleports++ }
            }
            val original = sceneTransform()
            handle.applyTransform(
                original.copy(root = original.root.copy(rotation = EulerRotation(45.0, 0.0, 0.0)))
            )
            assertEquals(0, teleports)
            handle.close()
        }
    }

    @Test
    fun `decorative displays do not simulate physics`() {
        RendererFixture().use { fixture ->
            val handle = create(fixture)
            val displays = fixture.instance.entities.filterIsInstance<NpcItemDisplay>()
            assertEquals(2, displays.size)
            assertTrue(displays.all { !it.hasPhysics() })
            handle.close()
        }
    }

    @Test
    fun `animation head look and body follow share a bounded update cadence`() {
        RendererFixture().use { fixture ->
            val player = fixture.player()
            val handle = create(fixture)
            fixture.instance.entities.filterIsInstance<NpcItemDisplay>().forEach {
                it.addViewer(player)
            }
            handle.startAnimation(LocalId("wave"), 0)
            player.connection.packets.clear()
            for (tick in 1..20) {
                handle.advanceAnimation(tick * 50L)
                val original = sceneTransform()
                handle.applyTransform(
                    original.copy(
                        root =
                            original.root.copy(rotation = EulerRotation(tick.toDouble(), 0.0, 0.0))
                    )
                )
                handle.applyHeadLook(tick * 2.0, 0.0)
            }
            val updates = player.connection.packets.filterIsInstance<EntityMetaDataPacket>()
            assertTrue(
                updates.size in 1..10,
                "Two parts emitted ${updates.size} updates in one second",
            )
            handle.close()
        }
    }

    @Test
    fun `vertical station distance excludes viewers in the same chunk`() {
        RendererFixture().use { fixture ->
            // The Stage platform is 33 blocks above the lobby floor.
            val far = fixture.player(Pos(0.0, 33.0, 0.0))
            val handle = create(fixture)
            val displays = fixture.instance.entities.filterIsInstance<NpcItemDisplay>()
            assertTrue(displays.all { far !in it.viewers })
            far.connection.packets.clear()
            handle.startAnimation(LocalId("wave"), 0)
            handle.advanceAnimation(500)
            assertTrue(far.connection.packets.filterIsInstance<EntityMetaDataPacket>().isEmpty())
            handle.close()
        }
    }

    @Test
    fun `invisible animation resumes at current elapsed time when a viewer approaches`() {
        RendererFixture().use { fixture ->
            val handle = create(fixture)
            val body =
                fixture.instance.entities.filterIsInstance<NpcItemDisplay>().minBy { it.entityId }
            handle.startAnimation(LocalId("wave"), 0)
            handle.advanceAnimation(500)
            assertEquals(0.7, (body.entityMeta as ItemDisplayMeta).translation.y(), 1e-6)
            val player = fixture.player()
            handle.advanceAnimation(1000)
            assertEquals(1.7, (body.entityMeta as ItemDisplayMeta).translation.y(), 1e-6)
            assertTrue(player in body.viewers)
            handle.close()
        }
    }

    @Test
    fun `small head targets accumulate until a visible angle changes`() {
        RendererFixture().use { fixture ->
            val player = fixture.player()
            val handle = create(fixture)
            fixture.instance.entities.filterIsInstance<NpcItemDisplay>().forEach {
                it.addViewer(player)
            }
            handle.startAnimation(LocalId("idle"), 0)
            player.connection.packets.clear()
            for (tick in 1..3) {
                handle.applyHeadLook(tick * 0.5, 0.0)
                handle.advanceAnimation(tick * 200L)
            }
            assertTrue(player.connection.packets.filterIsInstance<EntityMetaDataPacket>().isEmpty())
            handle.applyHeadLook(2.0, 0.0)
            handle.advanceAnimation(800)
            val updates = player.connection.packets.filterIsInstance<EntityMetaDataPacket>()
            assertEquals(1, updates.size, "Only the head subtree should change")
            handle.close()
        }
    }

    @Test
    fun `range reentry restores current pose and personal scale and glow`() {
        RendererFixture().use { fixture ->
            val player = fixture.player()
            val handle = create(fixture)
            val body =
                fixture.instance.entities.filterIsInstance<NpcItemDisplay>().minBy { it.entityId }
            handle.startAnimation(LocalId("wave"), 0)
            handle.applyViewerState(player, SceneViewerVisualState(2.0, true))
            player.teleport(Pos(0.0, 60.0, 0.0)).join()
            handle.advanceAnimation(500)
            assertFalse(player in body.viewers)
            player.connection.packets.clear()
            handle.advanceAnimation(1000)
            assertTrue(player.connection.packets.filterIsInstance<EntityMetaDataPacket>().isEmpty())
            player.teleport(Pos.ZERO).join()
            handle.advanceAnimation(1500)
            assertTrue(player in body.viewers)
            val updates =
                player.connection.packets.filterIsInstance<EntityMetaDataPacket>().filter {
                    it.entityId() == body.entityId
                }
            val translation =
                updates
                    .mapNotNull {
                        it.entries()[MetadataDef.Display.TRANSLATION.index()]?.value()
                            as? net.minestom.server.coordinate.Point
                    }
                    .last()
            assertEquals(4.4, translation.y(), 1e-6)
            assertTrue(player.connection.flags(body.entityId).last() and 0x40 != 0)
            handle.close()
        }
    }

    @Test
    fun `visibility keeps updating while animation is stopped`() {
        RendererFixture().use { fixture ->
            val player = fixture.player()
            val handle = create(fixture)
            val body = fixture.instance.entities.filterIsInstance<NpcItemDisplay>().first()
            handle.stopAnimation(null)
            player.teleport(Pos(0.0, 60.0, 0.0)).join()
            repeat(5) { fixture.instance.tick(it * 50L) }
            assertFalse(player in body.viewers)
            player.teleport(Pos.ZERO).join()
            repeat(5) { fixture.instance.tick(250L + it * 50L) }
            assertTrue(player in body.viewers)
            handle.close()
        }
    }

    @Test
    fun `stopping animation commits the final queued head target`() {
        RendererFixture().use { fixture ->
            fixture.player()
            val handle = create(fixture)
            val head =
                fixture.instance.entities.filterIsInstance<NpcItemDisplay>().maxBy { it.entityId }
            val meta = head.entityMeta as ItemDisplayMeta
            handle.startAnimation(LocalId("idle"), 0)
            val before = meta.leftRotation.copyOf()
            handle.applyHeadLook(30.0, 0.0)
            handle.stopAnimation(LocalId("idle"))
            assertFalse(
                before.contentEquals(meta.leftRotation),
                "Stopped NPC retained an old head target",
            )
            val stopped = meta.leftRotation.copyOf()
            handle.applyHeadLook(30.0, 0.0)
            assertArrayEquals(stopped, meta.leftRotation)
            handle.close()
        }
    }

    @Test
    fun `a player at the same coordinates in another instance is outside station range`() {
        RendererFixture().use { first ->
            RendererFixture().use { other ->
                val player = first.player()
                assertTrue(
                    NpcRenderPolicy.distanceSquared(player, first.instance, Vec3(0.0, 0.0, 0.0)) <=
                        NpcRenderPolicy.VIEW_DISTANCE_SQUARED
                )
                assertFalse(
                    NpcRenderPolicy.distanceSquared(player, other.instance, Vec3(0.0, 0.0, 0.0)) <=
                        NpcRenderPolicy.VIEW_DISTANCE_SQUARED
                )
            }
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
