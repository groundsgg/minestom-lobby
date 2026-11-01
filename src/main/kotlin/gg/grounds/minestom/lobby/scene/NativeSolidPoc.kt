package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*
import net.minestom.server.collision.BoundingBox
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.*
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.metadata.golem.ShulkerMeta
import net.minestom.server.event.EventDispatcher
import net.minestom.server.event.EventFilter
import net.minestom.server.event.EventListener
import net.minestom.server.event.EventNode
import net.minestom.server.event.entity.EntityAttackEvent
import net.minestom.server.event.player.PlayerEntityInteractEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.instance.Instance
import net.minestom.server.network.packet.server.play.EntityPositionSyncPacket
import net.minestom.server.network.packet.server.play.SetPassengersPacket
import net.minestom.server.network.packet.server.play.data.PositionPath
import net.minestom.server.utils.Direction
import net.minestom.server.utils.entity.EntityUtils
import org.slf4j.LoggerFactory

/** Stage spike: only the fixed demo-solid station. The Scene collision guard stays active. */
internal class NativeSolidPoc(private val instance: Instance, private val npc: Npc) :
    AutoCloseable {
    val cubes = nativeSolidCubes(npc)
    private val diagnosticCenter = nativeSolidCenter(npc)

    private data class Pairing(val anchor: Entity, val body: LivingEntity, val position: Pos)

    private val pairs =
        cubes.map { cube ->
            val anchor =
                Entity(EntityType.BLOCK_DISPLAY).apply {
                    setNoGravity(true)
                    setHasPhysics(false)
                    setAutoViewable(false)
                    boundingBox = BoundingBox(0.0, 0.0, 0.0)
                }
            val body =
                object : LivingEntity(EntityType.SHULKER) {
                        private val viewerRevisions = ConcurrentHashMap<java.util.UUID, Long>()
                        private val revision = AtomicLong()

                        override fun updateNewViewer(player: Player) {
                            val token = revision.incrementAndGet()
                            viewerRevisions[player.uuid] = token
                            super.updateNewViewer(player)
                            this@NativeSolidPoc.instance.scheduler().scheduleNextTick {
                                synchronized(this@NativeSolidPoc) {
                                    val target = this@NativeSolidPoc.instance
                                    if (
                                        !closed &&
                                            !isRemoved &&
                                            !anchor.isRemoved &&
                                            this.instance === target &&
                                            anchor.instance === target &&
                                            vehicle === anchor &&
                                            player.instance === target &&
                                            player in viewers &&
                                            player in anchor.viewers &&
                                            viewerRevisions[player.uuid] == token
                                    ) {
                                        // Mounting alone leaves the spawn pose grid-snapped.
                                        // Reassert the authored pose explicitly after the mount.
                                        player.sendPacket(
                                            SetPassengersPacket(anchor.entityId, listOf(entityId))
                                        )
                                        player.sendPacket(
                                            EntityPositionSyncPacket(
                                                entityId,
                                                PositionPath.Linear(position),
                                                position.yaw(),
                                                position.pitch(),
                                                isOnGround,
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        override fun updateOldViewer(player: Player) {
                            viewerRevisions.remove(player.uuid)
                            super.updateOldViewer(player)
                        }
                    }
                    .apply {
                        setNoGravity(true)
                        setHasPhysics(false)
                        setAutoViewable(false)
                        isInvisible = true
                        isSilent = true
                        isInvulnerable = true
                        getAttribute(Attribute.SCALE).baseValue = cube.side
                        boundingBox = BoundingBox(cube.side, cube.side, cube.side)
                        (entityMeta as ShulkerMeta).apply {
                            attachFace = Direction.DOWN
                            shieldHeight = 0
                        }
                    }
            Pairing(anchor, body, Pos(cube.x, cube.y, cube.z))
        }
    private val bodies = pairs.map { it.body.uuid }.toSet()
    private val packetTrace =
        NativeSolidPacketTrace(
            instance,
            pairs.flatMap { listOf(it.anchor.entityId, it.body.entityId) }.toSet(),
        )
    private val node = EventNode.type("native-solid-poc", EventFilter.INSTANCE)
    private val beforeMove =
        EventNode.type("native-solid-inspect-before", EventFilter.INSTANCE)
            .setPriority(Int.MIN_VALUE)
    private val afterMove =
        EventNode.type("native-solid-inspect-after", EventFilter.INSTANCE)
            .setPriority(Int.MAX_VALUE)
    private val movements = mutableMapOf<java.util.UUID, Pair<Pos, Pos>>()
    private val inspected = mutableSetOf<java.util.UUID>()
    private var diagnosticBudget = 30
    private var lastDiagnostic = 0L
    @Volatile private var closed = false
    private var started = false
    val ready = CompletableFuture<Void>()

    @Synchronized
    fun start(): CompletableFuture<Void> {
        check(!started && !closed)
        started = true
        packetTrace.start()
        beforeMove.addListener(PlayerMoveEvent::class.java) { event ->
            if (
                !closed &&
                    ready.isDone &&
                    !ready.isCompletedExceptionally &&
                    diagnosticBudget > 0 &&
                    event.player.position.distance(diagnosticCenter) < 8.0
            )
                movements[event.player.uuid] = event.player.position to event.newPosition
        }
        afterMove.addListener(
            EventListener.builder(PlayerMoveEvent::class.java)
                .ignoreCancelled(false)
                .handler { event ->
                    val move = movements.remove(event.player.uuid) ?: return@handler
                    val corrected = event.isCancelled || move.second != event.newPosition
                    val now = System.nanoTime()
                    if (
                        diagnosticBudget > 0 &&
                            (event.player.uuid !in inspected ||
                                now - lastDiagnostic >= 1_000_000_000L)
                    ) {
                        diagnosticBudget--
                        inspected.add(event.player.uuid)
                        lastDiagnostic = now
                        LoggerFactory.getLogger(NativeSolidPoc::class.java)
                            .info(
                                "Native SOLID diagnostic playerEntity={} old={} requested={} eventPosition={} cancelled={} corrected={} state={}",
                                event.player.entityId,
                                move.first,
                                move.second,
                                event.newPosition,
                                event.isCancelled,
                                corrected,
                                inspect(event.player),
                            )
                    }
                }
                .build()
        )
        instance.eventNode().addChild(beforeMove)
        instance.eventNode().addChild(afterMove)
        node.addListener(PlayerEntityInteractEvent::class.java) { event ->
            if (!closed && event.target.uuid in bodies)
                sceneAnchor()?.let { target ->
                    EventDispatcher.call(
                        PlayerEntityInteractEvent(event.player, target, event.hand, Vec.ZERO)
                    )
                }
        }
        node.addListener(EntityAttackEvent::class.java) { event ->
            if (!closed && event.target.uuid in bodies && event.entity is Player)
                sceneAnchor()?.let { target ->
                    EventDispatcher.call(EntityAttackEvent(event.entity, target))
                }
        }
        instance.eventNode().addChild(node)
        val attachments =
            pairs.flatMap { pair ->
                val offset = EntityUtils.getPassengerPositionOffset(pair.anchor, pair.body, 0)
                listOf(
                    attach(pair.anchor, pair.position.sub(offset)),
                    attach(pair.body, pair.position),
                )
            }
        CompletableFuture.allOf(*attachments.toTypedArray()).whenComplete { _, failure ->
            if (closed || failure != null) {
                ready.completeExceptionally(
                    failure ?: IllegalStateException("Native collision probe closed")
                )
                close()
                return@whenComplete
            }
            instance.scheduler().scheduleNextTick {
                synchronized(this) {
                    if (closed) {
                        close()
                        ready.completeExceptionally(
                            failure ?: IllegalStateException("Native collision probe closed")
                        )
                    } else
                        try {
                            pairs.forEach { pair ->
                                pair.anchor.addPassenger(pair.body)
                                check(pair.body.position.distance(pair.position) < 1e-6) {
                                    "Carrier offset differs from collider position"
                                }
                                pair.anchor.isAutoViewable = true
                                pair.body.isAutoViewable = true
                            }
                            ready.complete(null)
                        } catch (error: Throwable) {
                            close()
                            ready.completeExceptionally(error)
                        }
                }
            }
        }
        return ready
    }

    internal fun inspect(player: Player): NativeSolidState =
        NativeSolidState(
            pairs.size,
            pairs.count { !it.body.isRemoved && it.body.instance === instance },
            pairs.count { !it.anchor.isRemoved && it.anchor.instance === instance },
            pairs.count { it.body.vehicle === it.anchor },
            pairs.count { player in it.body.viewers },
            pairs.count { player in it.anchor.viewers },
            pairs.maxOf { it.body.position.distance(it.position) },
            pairs.map {
                "body=${it.body.entityId} carrier=${it.anchor.entityId} pos=${it.body.position} " +
                    "box=${it.body.boundingBox} scale=${it.body.getAttributeValue(Attribute.SCALE)} " +
                    "health=${it.body.health} expected=${it.position} " +
                    "carrierPos=${it.anchor.position} carrierBox=${it.anchor.boundingBox}"
            },
            "playerBox=${player.boundingBox}; guard=" +
                instance.entities
                    .filter { it.entityType == EntityType.INTERACTION }
                    .filter { it.position.distance(diagnosticCenter) < 3.0 }
                    .minByOrNull { it.position.distanceSquared(diagnosticCenter) }
                    ?.let { "id=${it.entityId} pos=${it.position} box=${it.boundingBox}" },
        )

    private fun attach(entity: Entity, position: Pos): CompletableFuture<Void> {
        val stage =
            try {
                entity.setInstance(instance, position)
            } catch (error: Throwable) {
                CompletableFuture.failedFuture<Void>(error)
            }
        return stage.whenComplete { _, error ->
            if (error != null) {
                ready.completeExceptionally(error)
                close()
            }
            if (closed) entity.remove()
        }
    }

    private fun sceneAnchor(): Entity? {
        val expected = nativeSolidCenter(npc)
        return instance.entities.firstOrNull {
            it.entityType == EntityType.INTERACTION &&
                it.position.distance(expected) < 1e-4 &&
                it.boundingBox.height() > 0
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        packetTrace.close()
        ready.completeExceptionally(IllegalStateException("Native collision probe closed"))
        instance.eventNode().removeChild(node)
        instance.eventNode().removeChild(beforeMove)
        instance.eventNode().removeChild(afterMove)
        movements.clear()
        inspected.clear()
        pairs.forEach {
            it.body.remove()
            it.anchor.remove()
        }
    }
}

internal data class NativeSolidState(
    val expected: Int,
    val liveBodies: Int,
    val liveCarriers: Int,
    val mounted: Int,
    val bodiesVisible: Int,
    val carriersVisible: Int,
    val maximumPositionError: Double,
    val details: List<String>,
    val playerAndGuard: String,
)

internal data class NativeSolidCube(val x: Double, val y: Double, val z: Double, val side: Double)

private fun nativeSolidBounds(npc: Npc): Pair<Vec3, Vec3> {
    require(npc.look == LookBehavior.Fixed && npc.collision.mode == NpcCollisionMode.SOLID)
    val t = npc.transform
    require(t.rotation.pitch == 0.0 && t.rotation.roll == 0.0)
    val yaw = Math.toRadians(t.rotation.yaw)
    val c = cos(yaw)
    val s = sin(yaw)
    val points = buildList {
        for (x in listOf(-0.5, 0.5)) for (y in listOf(-0.5, 0.5)) for (z in listOf(-0.5, 0.5)) {
            val b = npc.interactionBounds
            val px = (b.center.x + b.size.x * x) * t.scale.x
            val py = (b.center.y + b.size.y * y) * t.scale.y
            val pz = (b.center.z + b.size.z * z) * t.scale.z
            add(
                Vec3(
                    t.position.x + c * px + s * pz,
                    t.position.y + py,
                    t.position.z - s * px + c * pz,
                )
            )
        }
    }
    return Vec3(points.minOf { it.x }, points.minOf { it.y }, points.minOf { it.z }) to
        Vec3(points.maxOf { it.x }, points.maxOf { it.y }, points.maxOf { it.z })
}

private fun nativeSolidCenter(npc: Npc): Pos {
    val (a, b) = nativeSolidBounds(npc)
    return Pos((a.x + b.x) / 2, a.y, (a.z + b.z) / 2)
}

internal fun nativeSolidCubes(npc: Npc): List<NativeSolidCube> {
    val (a, b) = nativeSolidBounds(npc)
    // A tiny contact skin keeps legitimate client sliding outside the legacy correction guard.
    val skin = 0.004
    val width = b.x - a.x + skin * 2
    val height = b.y - a.y
    val depth = b.z - a.z + skin * 2
    val side = minOf(width, height, depth).toFloat().toDouble()
    require(side.isFinite() && side >= 0.0625 && side <= 16.0)
    val nx = ceil(width / side).toInt()
    val ny = ceil(height / side).toInt()
    val nz = ceil(depth / side).toInt()
    require(nx.toLong() * ny * nz <= 32) { "Native collision spike supports at most 32 cubes" }
    fun at(min: Double, length: Double, n: Int, i: Int) =
        min + if (n == 1) 0.0 else (length - side) * i / (n - 1)
    return buildList {
        for (x in 0 until nx) for (y in 0 until ny) for (z in 0 until nz) add(
            NativeSolidCube(
                at(a.x - skin, width, nx, x) + side / 2,
                at(a.y, height, ny, y),
                at(a.z - skin, depth, nz, z) + side / 2,
                side,
            )
        )
    }
}
