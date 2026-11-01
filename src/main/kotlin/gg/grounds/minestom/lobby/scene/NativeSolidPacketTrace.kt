package gg.grounds.minestom.lobby.scene

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.event.EventFilter
import net.minestom.server.event.EventListener
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerPacketOutEvent
import net.minestom.server.instance.Instance
import net.minestom.server.network.packet.server.ServerPacket
import net.minestom.server.network.packet.server.play.*
import org.slf4j.LoggerFactory

/** Stage-only, finite trace of the twelve known native collision entity IDs. */
internal class NativeSolidPacketTrace(
    private val instance: Instance,
    private val ids: Set<Int>,
    private val limit: Int = 256,
    private val sink: (String) -> Unit = {
        LoggerFactory.getLogger(NativeSolidPacketTrace::class.java).info(it)
    },
) : AutoCloseable {
    private val count = AtomicInteger()
    private val observed = ConcurrentHashMap.newKeySet<UUID>()
    private val positions = ConcurrentHashMap.newKeySet<Pair<UUID, Int>>()
    private val node =
        EventNode.type("native-solid-packet-trace", EventFilter.PLAYER).setPriority(Int.MAX_VALUE)
    @Volatile private var closed = false

    fun start() {
        node.addListener(
            EventListener.builder(PlayerPacketOutEvent::class.java)
                .ignoreCancelled(false)
                .handler { capture(it.player, it.packet, it.isCancelled) }
                .build()
        )
        MinecraftServer.getGlobalEventHandler().addChild(node)
    }

    internal fun capture(player: Player, packet: ServerPacket, cancelled: Boolean) {
        if (closed || count.get() >= limit) return
        val detail =
            when (packet) {
                is SpawnEntityPacket ->
                    if (packet.entityId() in ids) {
                        if (!cancelled) positions.remove(player.uuid to packet.entityId())
                        "spawn id=${packet.entityId()} type=${packet.type()} pos=${packet.position()}"
                    } else null
                is EntityMetaDataPacket ->
                    if (packet.entityId() in ids)
                        "metadata id=${packet.entityId()} flags=${packet.entries()[0]?.value()} keys=${packet.entries().keys}"
                    else null
                is EntityAttributesPacket ->
                    if (packet.entityId() in ids)
                        "attributes id=${packet.entityId()} scale=${packet.properties().firstOrNull { it.attribute() == Attribute.SCALE }?.value()}"
                    else null
                is SetPassengersPacket ->
                    if (packet.vehicleEntityId() in ids)
                        "mount carrier=${packet.vehicleEntityId()} passengers=${packet.passengersId()}"
                    else null
                is DestroyEntitiesPacket ->
                    packet
                        .entityIds()
                        .filter { it in ids }
                        .takeIf { it.isNotEmpty() }
                        ?.let { "destroy ids=$it" }
                is EntityPositionSyncPacket ->
                    if (
                        packet.entityId() in ids &&
                            (cancelled || positions.add(player.uuid to packet.entityId()))
                    )
                        "first-position id=${packet.entityId()} path=${packet.position()}"
                    else null
                is JoinGamePacket,
                is RespawnPacket ->
                    if (player.instance === instance || player.uuid in observed)
                        "world-reset type=${packet.javaClass.simpleName}"
                    else null
                else -> null
            } ?: return
        observed.add(player.uuid)
        val sequence = count.incrementAndGet()
        if (sequence <= limit)
            sink(
                "Native SOLID packet seq=$sequence playerEntity=${player.entityId} cancelled=$cancelled $detail"
            )
    }

    override fun close() {
        closed = true
        MinecraftServer.getGlobalEventHandler().removeChild(node)
        observed.clear()
        positions.clear()
    }
}
