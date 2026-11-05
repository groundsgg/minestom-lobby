package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
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
    @Test
    fun `smith and table include separate solid prop volumes without enclosing empty space`() {
        val cases =
            listOf(
                Triple(
                    "grounds:npc/guildfolk/forge_master",
                    Vec3(0.0, 0.5, 1.0),
                    Vec3(0.0, 1.7, 1.0),
                ),
                Triple(
                    "grounds:npc/craftmasters/dismantler",
                    Vec3(0.75, 0.6, 1.0),
                    Vec3(0.75, 1.7, 1.0),
                ),
            )
        for ((asset, occupied, free) in cases) {
            val station =
                npc(
                    asset,
                    Transform(
                        Vec3(0.0, 0.0, 0.0),
                        EulerRotation(0.0, 0.0, 0.0),
                        Vec3(1.0, 1.0, 1.0),
                    ),
                )
            val cubes = nativeSolidCubes(station)
            fun covered(p: Vec3) =
                cubes.any {
                    abs(p.x - it.x) <= it.side / 2 &&
                        p.y >= it.y &&
                        p.y <= it.y + it.side &&
                        abs(p.z - it.z) <= it.side / 2
                }
            assertTrue(covered(occupied), "Missing solid prop for $asset")
            assertFalse(covered(free), "Combined body/prop box blocks empty space for $asset")
            assertTrue(covered(Vec3(0.0, 1.7, 0.0)), "NPC body must remain solid")
            assertTrue(cubes.size <= 32)
        }
    }

    private fun npc(
        asset: String = "test:body",
        at: Transform =
            Transform(Vec3(1.25, 173.0, 2.75), EulerRotation(180.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
        id: String = "demo-solid",
    ) =
        Npc(
            LocalId(id),
            null,
            at,
            body = AssetKey(asset),
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

    @Test
    fun `multiple native stations forward to their own anchors and remove every collider`() {
        MinecraftServer.init()
        RendererFixture().use { fixture ->
            val player = fixture.player(Pos(2.5, 0.0, 5.0))
            val stations =
                listOf(
                    npc(
                        "grounds:npc/guildfolk/forge_master",
                        Transform(
                            Vec3(0.0, 0.0, 0.0),
                            EulerRotation(0.0, 0.0, 0.0),
                            Vec3(1.0, 1.0, 1.0),
                        ),
                        "demo-fixed",
                    ),
                    npc(
                        "grounds:npc/craftmasters/dismantler",
                        Transform(
                            Vec3(5.0, 0.0, 0.0),
                            EulerRotation(0.0, 0.0, 0.0),
                            Vec3(1.0, 1.0, 1.0),
                        ),
                        "demo-hands",
                    ),
                )
            val anchors =
                stations.map { station ->
                    Entity(EntityType.INTERACTION).also {
                        it.setBoundingBox(1.0, 2.0, 0.625)
                        it.setInstance(
                                fixture.instance,
                                Pos(station.transform.position.x, 0.0, 0.0),
                            )
                            .join()
                    }
                }
            val counts = IntArray(2)
            val observer = EventNode.type("multi-probe-observer", EventFilter.INSTANCE)
            observer.addListener(PlayerEntityInteractEvent::class.java) { event ->
                anchors.indexOf(event.target).takeIf { it >= 0 }?.let { counts[it]++ }
            }
            fixture.instance.eventNode().addChild(observer)
            val probes = stations.map { NativeSolidPoc(fixture.instance, it) }
            val ready = probes.map { it.start() }
            awaitReady(fixture, ready)
            val bodies = nativeBodies(fixture)
            val carriers = bodies.map { requireNotNull(it.vehicle) }
            assertEquals(probes.sumOf { it.cubes.size }, bodies.size)
            val first = bodies.first { it.position.x() < 2.5 }
            val second = bodies.first { it.position.x() > 2.5 }
            EventDispatcher.call(
                PlayerEntityInteractEvent(
                    player,
                    first,
                    PlayerHand.MAIN,
                    net.minestom.server.coordinate.Vec.ZERO,
                )
            )
            assertArrayEquals(intArrayOf(1, 0), counts)
            EventDispatcher.call(
                PlayerEntityInteractEvent(
                    player,
                    second,
                    PlayerHand.MAIN,
                    net.minestom.server.coordinate.Vec.ZERO,
                )
            )
            assertArrayEquals(intArrayOf(1, 1), counts)
            probes.forEach { it.close() }
            assertTrue((bodies + carriers).all { it.isRemoved })
            EventDispatcher.call(
                PlayerEntityInteractEvent(
                    player,
                    first,
                    PlayerHand.MAIN,
                    net.minestom.server.coordinate.Vec.ZERO,
                )
            )
            assertArrayEquals(intArrayOf(1, 1), counts)
        }
    }

    @Test
    fun `probe readiness keeps ticking after delayed chunk loading`() {
        MinecraftServer.init()
        RendererFixture().use { fixture ->
            ChunkGate().use { gate ->
                gate.install(fixture.instance, 0)
                NativeSolidPoc(fixture.instance, npc()).use { probe ->
                    val ready = probe.start()
                    assertTrue(gate.entered.await(3, TimeUnit.SECONDS))
                    // Exceed the former fixed tick budget while chunk I/O is still pending.
                    repeat(200) { fixture.instance.tick(it * 50L) }
                    assertFalse(ready.isDone)
                    gate.close()
                    assertTrue(gate.settled.await(3, TimeUnit.SECONDS))
                    awaitReady(fixture, listOf(ready))
                    assertTrue(ready.isDone)
                    assertEquals(probe.cubes.size, nativeBodies(fixture).size)
                }
            }
        }
    }

    private fun nativeBodies(fixture: RendererFixture) =
        fixture.instance.entities.filterIsInstance<LivingEntity>().filter {
            it.entityType == EntityType.SHULKER
        }

    private fun awaitReady(fixture: RendererFixture, ready: List<CompletableFuture<Void>>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var tick = 0L
        while (ready.any { !it.isDone } && System.nanoTime() < deadline) {
            fixture.instance.tick(tick++ * 50L)
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }
        assertTrue(ready.all { it.isDone }, "Native probes did not become ready within five seconds")
        ready.forEach { it.get(1, TimeUnit.SECONDS) }
    }

    private fun readyProbe(fixture: RendererFixture): NativeSolidPoc {
        val probe = NativeSolidPoc(fixture.instance, npc())
        val ready = probe.start()
        awaitReady(fixture, listOf(ready))
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
            awaitReady(fixture, listOf(ready))
            fixture.instance.tick(10_000L)
            val bodies =
                fixture.instance.entities.filterIsInstance<LivingEntity>().filter {
                    it.entityType == EntityType.SHULKER
                }
            assertEquals(probe.cubes.size, bodies.size)
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
