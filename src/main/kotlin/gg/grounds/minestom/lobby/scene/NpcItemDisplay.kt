package gg.grounds.minestom.lobby.scene

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

    init {
        metadata = MetadataHolder { changes ->
            synchronized(lock) {
                if (!isRemoved) {
                    val common = EntityMetaDataPacket(entityId, changes)
                    viewers.forEach { player ->
                        val entries = personalized(player, changes)
                        player.sendPacket(
                            if (entries === changes) common
                            else EntityMetaDataPacket(entityId, entries)
                        )
                    }
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

    fun sendVisualState(player: Player, resetInterpolation: Boolean) {
        val meta = entityMeta as ItemDisplayMeta
        val flags = metadataPacket.entries()[0] ?: Metadata.Byte(0)
        val fields =
            mutableMapOf<Int, Metadata.Entry<*>>(
                0 to flags,
                MetadataDef.Display.TRANSLATION.index() to Metadata.Vector3(meta.translation),
                MetadataDef.Display.SCALE.index() to Metadata.Vector3(meta.scale),
            )
        if (resetInterpolation)
            fields[MetadataDef.Display.INTERPOLATION_DELAY.index()] = Metadata.VarInt(0)
        player.sendPacket(EntityMetaDataPacket(entityId, personalized(player, fields)))
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
