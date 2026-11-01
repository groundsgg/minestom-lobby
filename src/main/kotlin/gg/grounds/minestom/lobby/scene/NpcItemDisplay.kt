package gg.grounds.minestom.lobby.scene

import java.util.UUID
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Metadata
import net.minestom.server.entity.MetadataDef
import net.minestom.server.entity.MetadataHolder
import net.minestom.server.entity.Player
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket

internal val DEFAULT_NPC_VISUAL = 1.0 to false

/** All pose updates pass through this per-viewer path, including animation and tracking. */
internal class NpcItemDisplay(private val lock: Any) : Entity(EntityType.ITEM_DISPLAY) {
    var viewerState: ((Player) -> Unit)? = null
    var visualState: ((Player) -> Pair<Double, Boolean>)? = null
    var renderMetrics: NpcModelMetrics? = null
    private var pendingUpdates: NpcPendingMetadata? = null

    init {
        metadata = MetadataHolder { changes ->
            synchronized(lock) {
                if (!isRemoved) {
                    val pending = pendingUpdates
                    if (pending == null) sendMetadata(changes, viewers) else pending.merge(changes)
                }
            }
        }
        entityMeta = ItemDisplayMeta(this, metadata)
        setNoGravity(true)
        setHasPhysics(false)
        // Explicit anchor moves still synchronize immediately; stationary visuals need no
        // heartbeat.
        setSynchronizationTicks(Long.MAX_VALUE / 2)
    }

    fun configureUpdates(intervals: Set<Long>) {
        val pending = pendingUpdates ?: NpcPendingMetadata().also { pendingUpdates = it }
        pending.configure(intervals)
    }

    fun flushUpdates(interval: Long, intervals: Map<UUID, Long>) {
        pendingUpdates?.drain(interval)?.let { sendMetadata(it, viewers, interval, intervals) }
    }

    fun clearUpdates() {
        pendingUpdates?.clear()
    }

    private fun sendMetadata(
        changes: Map<Int, Metadata.Entry<*>>,
        recipients: Collection<Player>,
        interval: Long? = null,
        intervals: Map<UUID, Long>? = null,
        kind: NpcMetadataKind = NpcMetadataKind.DELTA,
    ) {
        if (recipients.isEmpty()) return
        val common = EntityMetaDataPacket(entityId, changes)
        recipients.forEach { player ->
            if (interval != null && intervals?.get(player.uuid) != interval) return@forEach
            val entries = personalized(player, changes)
            player.sendPacket(
                if (entries === changes) common else EntityMetaDataPacket(entityId, entries)
            )
            renderMetrics?.metadata(kind, entries.size)
        }
    }

    private fun personalized(
        player: Player,
        changes: Map<Int, Metadata.Entry<*>>,
    ): Map<Int, Metadata.Entry<*>> {
        val state = visualState?.invoke(player) ?: DEFAULT_NPC_VISUAL
        val flags = (changes[0]?.value() as? Byte ?: 0).toInt()
        val desiredFlags = if (state.second) flags or 0x40 else flags and 0xBF
        val translation = MetadataDef.Display.TRANSLATION.index()
        val scale = MetadataDef.Display.SCALE.index()
        if (
            (0 !in changes || flags == desiredFlags) &&
                (state.first == 1.0 || (translation !in changes && scale !in changes))
        )
            return changes
        val entries = changes.toMutableMap()
        if (0 in changes) {
            entries[0] = Metadata.Byte(desiredFlags.toByte())
        }
        val meta = entityMeta as ItemDisplayMeta
        if (translation in changes)
            entries[translation] = Metadata.Vector3(meta.translation.mul(state.first))
        if (scale in changes) entries[scale] = Metadata.Vector3(meta.scale.mul(state.first))
        return entries
    }

    fun sendVisualFlags(player: Player) {
        val flags = metadataPacket.entries()[0] ?: Metadata.Byte(0)
        sendMetadata(mapOf(0 to flags), listOf(player), kind = NpcMetadataKind.VISUAL)
    }

    fun sendPoseState(player: Player, interval: Long) {
        val meta = entityMeta as ItemDisplayMeta
        val flags = metadataPacket.entries()[0] ?: Metadata.Byte(0)
        val fields =
            mutableMapOf<Int, Metadata.Entry<*>>(
                0 to flags,
                MetadataDef.Display.TRANSLATION.index() to Metadata.Vector3(meta.translation),
                MetadataDef.Display.SCALE.index() to Metadata.Vector3(meta.scale),
                MetadataDef.Display.ROTATION_LEFT.index() to Metadata.Quaternion(meta.leftRotation),
                MetadataDef.Display.ROTATION_RIGHT.index() to
                    Metadata.Quaternion(meta.rightRotation),
                MetadataDef.Display.TRANSFORMATION_INTERPOLATION_DURATION.index() to
                    Metadata.VarInt((interval / 50).toInt()),
            )
        fields[MetadataDef.Display.INTERPOLATION_DELAY.index()] = Metadata.VarInt(0)
        sendMetadata(fields, listOf(player), kind = NpcMetadataKind.SNAPSHOT)
    }

    override fun updateNewViewer(player: Player) {
        synchronized(lock) {
            if (!isRemoved) {
                super.updateNewViewer(player)
                viewerState?.invoke(player)
            }
        }
    }
}
