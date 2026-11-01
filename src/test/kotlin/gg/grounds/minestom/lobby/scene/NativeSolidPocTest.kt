package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.*
import kotlin.math.abs
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.*
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.event.EventDispatcher
import net.minestom.server.event.EventFilter
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerEntityInteractEvent
import net.minestom.server.network.packet.server.play.SetPassengersPacket
import net.minestom.server.network.packet.server.play.SpawnEntityPacket
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeSolidPocTest {
    private fun npc() =
        Npc(
            LocalId("demo-solid"),
            null,
            Transform(Vec3(1.25, 173.0, 2.75), EulerRotation(180.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
            body = AssetKey("test:body"),
            label = null,
            labelOffset = Vec3(0.0, 2.0, 0.0),
            look = LookBehavior.Fixed,
            initialAnimation = null,
            interactionBounds = LocalBounds(Vec3(0.0, 1.0, 0.0), Vec3(1.0, 2.0, 0.625)),
            proximity = null,
            bindings = emptyList(),
            collision = NpcCollision(NpcCollisionMode.SOLID),
        )

    @Test
    fun `native cubes cover the authored hitbox without gaps`() {
        val cubes = nativeSolidCubes(npc())
        assertTrue(cubes.size <= 32)
        for (x in 0..10) for (y in 0..20) for (z in 0..10) {
            val px = 0.75 + x / 10.0
            val py = 173.0 + y / 10.0
            val pz = 2.4375 + z * 0.0625
            assertTrue(
                cubes.any {
                    abs(px - it.x) <= it.side / 2 + 1e-7 &&
                        py >= it.y - 1e-7 &&
                        py <= it.y + it.side + 1e-7 &&
                        abs(pz - it.z) <= it.side / 2 + 1e-7
                },
                "Hitbox has a gap at $px/$py/$pz",
            )
        }
    }

    @Test
    fun `native colliders are invisible scaled mounted and clean up while clicks reach the scene anchor`() {
        MinecraftServer.init()
        RendererFixture().use { fixture ->
            val player = fixture.player(Pos(1.25, 173.0, 5.0))
            val anchor = Entity(EntityType.INTERACTION)
            anchor.setBoundingBox(1.0, 2.0, 1.0)
            anchor.setInstance(fixture.instance, Pos(1.25, 173.0, 2.75)).join()
            val observer = EventNode.type("probe-observer", EventFilter.INSTANCE)
            var forwarded = 0
            observer.addListener(PlayerEntityInteractEvent::class.java) {
                if (it.target === anchor) forwarded++
            }
            fixture.instance.eventNode().addChild(observer)
            val probe = NativeSolidPoc(fixture.instance, npc())
            val ready = probe.start()
            repeat(200) {
                if (!ready.isDone) {
                    fixture.instance.tick(it * 50L)
                    Thread.yield()
                }
            }
            ready.join()
            val bodies =
                fixture.instance.entities.filterIsInstance<LivingEntity>().filter {
                    it.entityType == EntityType.SHULKER
                }
            assertEquals(probe.cubes.size, bodies.size)
            val lateViewer = fixture.player(Pos(1000.0, 173.0, 1000.0))
            // Exercise automatic tracking; Entity.addViewer is a single-entity manual API.
            lateViewer.teleport(Pos(1.25, 173.0, 5.0)).join()
            lateViewer.teleport(Pos(1000.0, 173.0, 1000.0)).join()
            lateViewer.teleport(Pos(1.25, 173.0, 5.0)).join()
            bodies.forEach { body ->
                assertTrue(
                    body.isInvisible && body.isSilent && body.isInvulnerable && body.hasNoGravity()
                )
                val vehicle = body.vehicle!!
                val packets = lateViewer.connection.packets.toList()
                val mount =
                    packets.indexOfLast {
                        it is SetPassengersPacket &&
                            it.vehicleEntityId() == vehicle.entityId &&
                            body.entityId in it.passengersId()
                    }
                val bodySpawn =
                    packets.indexOfLast {
                        it is SpawnEntityPacket && it.entityId() == body.entityId
                    }
                val carrierSpawn =
                    packets.indexOfLast {
                        it is SpawnEntityPacket && it.entityId() == vehicle.entityId
                    }
                assertTrue(
                    mount > bodySpawn &&
                        bodySpawn >= 0 &&
                        mount > carrierSpawn &&
                        carrierSpawn >= 0,
                    "Client must receive both spawns before their mount on retracking: mount=$mount body=$bodySpawn carrier=$carrierSpawn",
                )
                assertNotNull(body.vehicle)
                assertEquals(
                    body.boundingBox.height(),
                    body.getAttributeValue(Attribute.SCALE),
                    1e-7,
                )
                assertTrue(probe.cubes.any { body.position.distance(Pos(it.x, it.y, it.z)) < 1e-6 })
            }
            EventDispatcher.call(
                PlayerEntityInteractEvent(
                    player,
                    bodies.first(),
                    PlayerHand.MAIN,
                    net.minestom.server.coordinate.Vec.ZERO,
                )
            )
            assertEquals(1, forwarded)
            val owned = bodies + bodies.map { it.vehicle!! }
            probe.close()
            assertTrue(owned.all { it.isRemoved })
            assertFalse(anchor.isRemoved)
        }
    }
}
