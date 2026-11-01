package gg.grounds.minestom.lobby.scene

import gg.grounds.resourcepacks.npc.NpcModel
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.MetadataDef
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class NpcViewerUpdatesTest {
    private val model =
        NpcModel.parse(
            """{"schemaVersion":1,"asset":"grounds:npc/test/cohorts","bounds":{"center":[0,1,0],"size":[1,2,1]},"bones":[{"id":"body","parent":null,"origin":[0,0,0],"rotation":[0,0,0]},{"id":"head","name":"head","parent":"body","origin":[0,1.5,0],"rotation":[0,0,0]}],"parts":[{"bone":"body","model":"grounds:npc_parts/test/body","center":[0,0.7,0],"pivot":[0,0.7,0],"rotation":[0,0,0]},{"bone":"head","model":"grounds:npc_parts/test/head","center":[0,1.8,0],"pivot":[0,1.8,0],"rotation":[0,0,0]}],"animations":{"wave":{"loop":"loop","length":2,"tracks":{"body":{"position":[{"time":0,"value":[0,0,0],"interpolation":"linear"},{"time":2,"value":[0,2,0],"interpolation":"linear"}]}}}},"sounds":{}}"""
        )

    private fun create(
        fixture: RendererFixture,
        metrics: NpcRenderMetrics = NpcRenderMetrics(null),
    ): HeadLookRenderedAssetHandle {
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
                            setOf(LocalId("wave")),
                            model.bounds,
                            emptyMap(),
                        )
                ),
            )
        return LobbyNpcModelRenderers(assets, mapOf(model.asset to model), metrics)
            .rendererFor(model.asset, AssetKind.NPC_BODY)!!
            .create(
                SceneAssetRenderContext(
                    fixture.instance,
                    LocalId("cohorts"),
                    null,
                    model.asset,
                    sceneTransform(),
                )
            )
            .toCompletableFuture()
            .join() as HeadLookRenderedAssetHandle
    }

    private fun TestPlayer.updates() = connection.packets.filterIsInstance<EntityMetaDataPacket>()

    private fun EntityMetaDataPacket.translation() =
        entries()[MetadataDef.Display.TRANSLATION.index()]?.value() as? Point

    @Test
    fun `runtime metrics record shared work and only the packets actually dispatched`() {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        try {
            RendererFixture().use { fixture ->
                fixture.player()
                fixture.player(Pos(50.0, 0.0, 0.0))
                val handle = create(fixture, NpcRenderMetrics(registry))
                handle.startAnimation(LocalId("wave"), 0)
                val packets =
                    registry
                        .get("grounds.npc.metadata.packets")
                        .tags("model", model.asset.value, "kind", "delta")
                        .counter()
                val before = packets.count()
                handle.advanceAnimation(200)
                assertEquals(2.0, packets.count() - before)
                for (phase in listOf("pose", "transform", "visibility", "delivery")) assertTrue(
                    registry
                        .get("grounds.npc.work.duration")
                        .tags("model", model.asset.value, "phase", phase)
                        .timer()
                        .count() > 0
                )
                assertTrue(
                    registry.meters.all { meter ->
                        meter.id.tags.all { it.key in setOf("model", "phase", "kind") }
                    }
                )
                assertTrue(registry.meters.size <= 10)
                handle.close()
            }
        } finally {
            registry.close()
        }
    }

    @Test
    fun `near medium and far viewers receive their own cadence from one shared pose`() {
        RendererFixture().use { fixture ->
            val viewers =
                listOf(
                    fixture.player(),
                    fixture.player(Pos(24.0, 0.0, 0.0)),
                    fixture.player(Pos(50.0, 0.0, 0.0)),
                )
            val handle = create(fixture)
            handle.startAnimation(LocalId("wave"), 0)
            for ((viewer, duration) in viewers.zip(listOf(4, 5, 10))) {
                val initializedDuration =
                    viewer
                        .updates()
                        .mapNotNull {
                            it.entries()[
                                    MetadataDef.Display.TRANSFORMATION_INTERPOLATION_DURATION
                                        .index()]
                                ?.value() as? Int
                        }
                        .last()
                assertEquals(duration, initializedDuration)
            }
            viewers.forEach { it.connection.packets.clear() }
            for (tick in 1..20) handle.advanceAnimation(tick * 50L)
            assertEquals(listOf(10, 8, 4), viewers.map { it.updates().size })
            assertTrue(
                viewers
                    .flatMap { it.updates() }
                    .all {
                        MetadataDef.Display.TRANSFORMATION_INTERPOLATION_DURATION.index() !in
                            it.entries()
                    },
                "A cohort duration is constant until its full initialization/transition snapshot",
            )
            handle.close()
        }
    }

    @Test
    fun `far flush retains a one time rotation omitted from later common deltas`() {
        RendererFixture().use { fixture ->
            fixture.player()
            val far = fixture.player(Pos(50.0, 0.0, 0.0))
            val handle = create(fixture)
            handle.startAnimation(LocalId("wave"), 0)
            far.connection.packets.clear()
            handle.applyHeadLook(35.0, 0.0)
            handle.advanceAnimation(200)
            val head =
                fixture.instance.entities.filterIsInstance<NpcItemDisplay>().maxBy { it.entityId }
            val expected = (head.entityMeta as ItemDisplayMeta).leftRotation.copyOf()
            assertTrue(far.updates().isEmpty(), "Far viewer was sent the near frame")
            handle.advanceAnimation(400)
            handle.advanceAnimation(500)
            val update = far.updates().single { it.entityId() == head.entityId }
            assertArrayEquals(
                expected,
                update.entries()[MetadataDef.Display.ROTATION_LEFT.index()]?.value() as? FloatArray,
            )
            handle.close()
        }
    }

    @Test
    fun `stop flushes final pose and drains queued changes before the next deadline`() {
        RendererFixture().use { fixture ->
            fixture.player()
            val far = fixture.player(Pos(50.0, 0.0, 0.0))
            val handle = create(fixture)
            val body =
                fixture.instance.entities.filterIsInstance<NpcItemDisplay>().minBy { it.entityId }
            handle.startAnimation(LocalId("wave"), 0)
            handle.advanceAnimation(200)
            far.connection.packets.clear()
            handle.stopAnimation(LocalId("wave"))
            assertEquals(
                0.7,
                far.updates().last { it.entityId() == body.entityId }.translation()!!.y(),
                1e-6,
            )
            far.connection.packets.clear()
            handle.advanceAnimation(1000)
            assertTrue(far.updates().isEmpty())
            handle.close()
        }
    }

    @Test
    fun `backward seek immediately delivers a coherent pose and rebases the deadline`() {
        RendererFixture().use { fixture ->
            fixture.player()
            val far = fixture.player(Pos(50.0, 0.0, 0.0))
            val handle = create(fixture)
            val body =
                fixture.instance.entities.filterIsInstance<NpcItemDisplay>().minBy { it.entityId }
            handle.startAnimation(LocalId("wave"), 0)
            handle.advanceAnimation(200)
            handle.advanceAnimation(400)
            far.connection.packets.clear()
            handle.advanceAnimation(100)
            assertEquals(
                0.8,
                far.updates().last { it.entityId() == body.entityId }.translation()!!.y(),
                1e-6,
            )
            far.connection.packets.clear()
            handle.advanceAnimation(300)
            handle.advanceAnimation(500)
            handle.advanceAnimation(600)
            assertEquals(2, far.updates().size)
            handle.close()
        }
    }

    @Test
    fun `joining an occupied far cohort does not postpone existing viewers deadline`() {
        RendererFixture().use { fixture ->
            fixture.player()
            val far = fixture.player(Pos(50.0, 0.0, 0.0))
            val handle = create(fixture)
            handle.startAnimation(LocalId("wave"), 0)
            handle.advanceAnimation(200)
            far.connection.packets.clear()
            fixture.player(Pos(48.0, 0.0, 0.0))
            handle.advanceAnimation(400)
            val npcIds =
                fixture.instance.entities
                    .filterIsInstance<NpcItemDisplay>()
                    .map { it.entityId }
                    .toSet()
            assertTrue(far.updates().none { it.entityId() in npcIds })
            handle.advanceAnimation(500)
            assertEquals(2, far.updates().count { it.entityId() in npcIds })
            handle.close()
        }
    }

    @Test
    fun `a published near delta is not mutated while the far cohort accumulates later fields`() {
        RendererFixture().use { fixture ->
            val near = fixture.player()
            fixture.player(Pos(50.0, 0.0, 0.0))
            val handle = create(fixture)
            val body =
                fixture.instance.entities.filterIsInstance<NpcItemDisplay>().minBy { it.entityId }
            handle.startAnimation(LocalId("wave"), 0)
            near.connection.packets.clear()
            handle.advanceAnimation(200)
            val first = near.updates().single { it.entityId() == body.entityId }
            val original = first.entries().toMap()
            handle.applyTransform(
                sceneTransform().let {
                    it.copy(root = it.root.copy(rotation = EulerRotation(35.0, 0.0, 0.0)))
                }
            )
            handle.advanceAnimation(400)
            handle.advanceAnimation(500)
            assertEquals(original, first.entries())
            assertEquals(0.9, first.translation()!!.y(), 1e-6)
            handle.close()
        }
    }

    @Test
    fun `queued packet entries remain immutable across later cohort flushes`() {
        RendererFixture().use { fixture ->
            fixture.player()
            val far = fixture.player(Pos(50.0, 0.0, 0.0))
            val handle = create(fixture)
            handle.startAnimation(LocalId("wave"), 0)
            far.connection.packets.clear()
            handle.advanceAnimation(200)
            handle.advanceAnimation(400)
            handle.advanceAnimation(500)
            val packet = far.updates().first()
            val position = packet.translation()
            assertNotNull(position)
            handle.advanceAnimation(600)
            handle.advanceAnimation(800)
            handle.advanceAnimation(1000)
            assertEquals(position, packet.translation())
            assertFalse(packet.entries().isEmpty())
            handle.close()
        }
    }

    @Test
    fun `retained distance bands still determine the shared sampling interval`() {
        for ((start, retained, interval) in
            listOf(Triple(0.0, 17.0, 200L), Triple(24.0, 34.0, 250L))) {
            RendererFixture().use { fixture ->
                val player = fixture.player(Pos(start, 0.0, 0.0))
                val handle = create(fixture)
                handle.startAnimation(LocalId("wave"), 0)
                player.teleport(Pos(retained, 0.0, 0.0)).join()
                player.connection.packets.clear()
                handle.advanceAnimation(interval)
                assertEquals(2, player.updates().size)
                val displays = fixture.instance.entities.filterIsInstance<NpcItemDisplay>()
                assertTrue(
                    displays.all {
                        (it.entityMeta as ItemDisplayMeta).transformationInterpolationDuration ==
                            (interval / 50).toInt()
                    }
                )
                handle.close()
            }
        }
    }

    @Test
    fun `immediate personal scale sends a complete pose with the slow viewers duration`() {
        RendererFixture().use { fixture ->
            fixture.player()
            val far = fixture.player(Pos(50.0, 0.0, 0.0))
            val handle = create(fixture)
            handle.startAnimation(LocalId("wave"), 0)
            handle.applyHeadLook(35.0, 0.0)
            handle.advanceAnimation(200)
            far.connection.packets.clear()
            handle.applyViewerState(far, SceneViewerVisualState(2.0, true))
            val poseFields =
                setOf(
                    MetadataDef.Display.TRANSLATION.index(),
                    MetadataDef.Display.SCALE.index(),
                    MetadataDef.Display.ROTATION_LEFT.index(),
                    MetadataDef.Display.ROTATION_RIGHT.index(),
                )
            assertEquals(2, far.updates().size)
            assertTrue(far.updates().all { it.entries().keys.containsAll(poseFields) })
            assertTrue(
                far.updates().all {
                    it.entries()[MetadataDef.Display.TRANSFORMATION_INTERPOLATION_DURATION.index()]
                        ?.value() == 10
                }
            )
            val head =
                fixture.instance.entities.filterIsInstance<NpcItemDisplay>().maxBy { it.entityId }
            val packet = far.updates().single { it.entityId() == head.entityId }
            assertEquals(
                (head.entityMeta as ItemDisplayMeta).translation.y() * 2,
                packet.translation()!!.y(),
                1e-6,
            )
            assertArrayEquals(
                (head.entityMeta as ItemDisplayMeta).leftRotation,
                packet.entries()[MetadataDef.Display.ROTATION_LEFT.index()]?.value() as? FloatArray,
            )
            handle.close()
        }
    }

    @Test
    fun `an empty far cohort discards its old deadline and reentry gets a current snapshot`() {
        RendererFixture().use { fixture ->
            fixture.player()
            val far = fixture.player(Pos(50.0, 0.0, 0.0))
            val handle = create(fixture)
            handle.startAnimation(LocalId("wave"), 0)
            handle.advanceAnimation(200)
            far.teleport(Pos(100.0, 0.0, 0.0)).join()
            handle.advanceAnimation(400)
            far.teleport(Pos(50.0, 0.0, 0.0)).join()
            handle.advanceAnimation(450)
            assertTrue(far.updates().isNotEmpty())
            far.connection.packets.clear()
            handle.advanceAnimation(500)
            assertTrue(far.updates().isEmpty())
            handle.advanceAnimation(700)
            handle.advanceAnimation(950)
            assertEquals(2, far.updates().size)
            handle.close()
        }
    }

    @Test
    fun `closing a handle discards pending viewer updates`() {
        RendererFixture().use { fixture ->
            fixture.player()
            val far = fixture.player(Pos(50.0, 0.0, 0.0))
            val handle = create(fixture)
            handle.startAnimation(LocalId("wave"), 0)
            handle.advanceAnimation(200)
            far.connection.packets.clear()
            handle.close()
            handle.advanceAnimation(1000)
            assertTrue(far.updates().isEmpty())
        }
    }

    @Test
    fun `highlight is immediate without advancing a slow viewers pending transform`() {
        RendererFixture().use { fixture ->
            fixture.player()
            val far = fixture.player(Pos(50.0, 0.0, 0.0))
            val handle = create(fixture)
            handle.startAnimation(LocalId("wave"), 0)
            handle.advanceAnimation(200)
            far.connection.packets.clear()
            handle.applyViewerState(far, SceneViewerVisualState(1.0, true))
            assertEquals(2, far.updates().size)
            assertTrue(far.updates().all { it.entries().keys == setOf(0) })
            assertTrue(
                far.updates().all { (it.entries()[0]!!.value() as Byte).toInt() and 0x40 != 0 }
            )
            handle.close()
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
