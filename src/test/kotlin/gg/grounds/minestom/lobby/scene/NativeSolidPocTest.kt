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
import net.minestom.server.network.packet.server.play.EntityPositionSyncPacket
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
    fun `deferred mounted pose is not sent after viewer leaves`() {
        MinecraftServer.init()
        RendererFixture().use { fixture ->
            val player = fixture.player(Pos(1000.0, 173.0, 1000.0))
            readyProbe(fixture).use {
                val bodyIds = nativeBodies(fixture).map { it.entityId }.toSet()
                val carrierIds = nativeBodies(fixture).map { it.vehicle!!.entityId }.toSet()
                player.teleport(Pos(1.25, 173.0, 5.0)).join()
                player.teleport(Pos(1000.0, 173.0, 1000.0)).join()
                player.connection.packets.clear()

                fixture.instance.tick(20_000L)

                assertTrue(
                    player.connection.packets.none {
                        it is EntityPositionSyncPacket && it.entityId() in bodyIds ||
                            it is SetPassengersPacket && it.vehicleEntityId() in carrierIds
                    },
                    "A departed viewer must not receive a deferred mount or pose",
                )
            }
        }
    }

    @Test
    fun `deferred mounted pose is not sent after probe closes`() {
        MinecraftServer.init()
        RendererFixture().use { fixture ->
            val player = fixture.player(Pos(1000.0, 173.0, 1000.0))
            readyProbe(fixture).use { probe ->
                val bodies = nativeBodies(fixture)
                val bodyIds = bodies.map { it.entityId }.toSet()
                val carrierIds = bodies.map { it.vehicle!!.entityId }.toSet()
                player.teleport(Pos(1.25, 173.0, 5.0)).join()
                probe.close()
                player.connection.packets.clear()

                fixture.instance.tick(20_000L)

                assertTrue(bodies.all { it.isRemoved })
                assertTrue(
                    player.connection.packets.none {
                        it is EntityPositionSyncPacket && it.entityId() in bodyIds ||
                            it is SetPassengersPacket && it.vehicleEntityId() in carrierIds
                    },
                    "A closed probe must not reassert removed entities",
                )
            }
        }
    }

    @Test
    fun `rapid viewer reentry coalesces pending mounted poses to the newest spawn`() {
        MinecraftServer.init()
        RendererFixture().use { fixture ->
            val player = fixture.player(Pos(1000.0, 173.0, 1000.0))
            readyProbe(fixture).use {
                val bodies = nativeBodies(fixture)
                player.connection.packets.clear()
                player.teleport(Pos(1.25, 173.0, 5.0)).join()
                player.teleport(Pos(1000.0, 173.0, 1000.0)).join()
                player.teleport(Pos(1.25, 173.0, 5.0)).join()

                fixture.instance.tick(20_000L)

                val packets = player.connection.packets.toList()
                for (body in bodies) {
                    val carrier = body.vehicle!!
                    val poses =
                        packets.withIndex().filter {
                            it.value is EntityPositionSyncPacket &&
                                (it.value as EntityPositionSyncPacket).entityId() == body.entityId
                        }
                    val spawn =
                        packets.indexOfLast {
                            it is SpawnEntityPacket && it.entityId() == body.entityId
                        }
                    val mount =
                        packets.indexOfLast {
                            it is SetPassengersPacket &&
                                it.vehicleEntityId() == carrier.entityId &&
                                body.entityId in it.passengersId()
                        }
                    assertEquals(1, poses.size, "Stale entry callback sent another pose")
                    assertTrue(spawn >= 0 && mount > spawn && poses.single().index > mount)
                }
            }
        }
    }

    private fun nativeBodies(fixture: RendererFixture) =
        fixture.instance.entities.filterIsInstance<LivingEntity>().filter {
            it.entityType == EntityType.SHULKER
        }

    private fun readyProbe(fixture: RendererFixture): NativeSolidPoc {
        val probe = NativeSolidPoc(fixture.instance, npc())
        val ready = probe.start()
        repeat(200) {
            if (!ready.isDone) {
                fixture.instance.tick(it * 50L)
                Thread.yield()
            }
        }
        ready.join()
        return probe
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
            fixture.instance.tick(10_000L)
            val bodies =
                fixture.instance.entities.filterIsInstance<LivingEntity>().filter {
                    it.entityType == EntityType.SHULKER
                }
            assertEquals(probe.cubes.size, bodies.size)
            val inspection = probe.inspect(player)
            assertEquals(probe.cubes.size, inspection.liveBodies)
            assertEquals(probe.cubes.size, inspection.liveCarriers)
            assertEquals(probe.cubes.size, inspection.mounted)
            assertEquals(probe.cubes.size, inspection.bodiesVisible)
            assertEquals(probe.cubes.size, inspection.carriersVisible)
            assertTrue(inspection.maximumPositionError < 1e-6)
            bodies.forEach { body ->
                val carrier = requireNotNull(body.vehicle)
                val initialPackets = player.connection.packets.toList()
                val bodySpawn =
                    initialPackets.indexOfLast {
                        it is SpawnEntityPacket && it.entityId() == body.entityId
                    }
                val carrierSpawn =
                    initialPackets.indexOfLast {
                        it is SpawnEntityPacket && it.entityId() == carrier.entityId
                    }
                val mount =
                    initialPackets.indexOfLast {
                        it is SetPassengersPacket &&
                            it.vehicleEntityId() == carrier.entityId &&
                            body.entityId in it.passengersId()
                    }
                val position =
                    initialPackets.indexOfLast {
                        it is EntityPositionSyncPacket && it.entityId() == body.entityId
                    }
                assertTrue(
                    position > mount,
                    "Mounted collider needs an explicit position after the mount: position=$position mount=$mount",
                )
                assertTrue(
                    bodySpawn >= 0 &&
                        carrierSpawn >= 0 &&
                        mount > bodySpawn &&
                        mount > carrierSpawn,
                    "Initial viewer needs both spawns before mounting: mount=$mount body=$bodySpawn carrier=$carrierSpawn",
                )
            }
            val lateViewer = fixture.player(Pos(1000.0, 173.0, 1000.0))
            // Exercise automatic tracking; Entity.addViewer is a single-entity manual API.
            lateViewer.teleport(Pos(1.25, 173.0, 5.0)).join()
            fixture.instance.tick(10_050L)
            lateViewer.teleport(Pos(1000.0, 173.0, 1000.0)).join()
            lateViewer.teleport(Pos(1.25, 173.0, 5.0)).join()
            fixture.instance.tick(10_100L)
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
                val position =
                    packets.indexOfLast {
                        it is EntityPositionSyncPacket && it.entityId() == body.entityId
                    }
                assertTrue(
                    position > mount,
                    "Retracked collider needs an explicit position after mounting: position=$position mount=$mount",
                )
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
