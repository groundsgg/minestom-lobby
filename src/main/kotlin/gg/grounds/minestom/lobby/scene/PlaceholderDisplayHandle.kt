package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.LocalBounds
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.minestom.RenderedAssetHandle
import gg.grounds.scene.minestom.SceneRenderTransform
import gg.grounds.scene.minestom.SceneViewerVisualState
import java.util.UUID
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Metadata
import net.minestom.server.entity.Player
import net.minestom.server.entity.metadata.display.BlockDisplayMeta
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket

internal class PlaceholderDisplayHandle(
    private val display: HighlightableBlockDisplay,
    private val bounds: LocalBounds,
) : RenderedAssetHandle {
    private val lock = display.viewerLock
    private val highlighted = mutableMapOf<UUID, Boolean>()
    private var closed = false
    private var transformRevision = 0L

    init {
        synchronized(lock) { display.viewerState = ::sendViewerState }
    }

    override fun applyTransform(transform: SceneRenderTransform) {
        synchronized(lock) {
            if (closed) return
            val revision = ++transformRevision
            val anchor = transform.root.position
            val position = Pos(anchor.x, anchor.y, anchor.z)
            val prepared = DisplayTransform.from(transform, bounds)
            // Load before changing either half of the transform. The pinned teleport runs
            // inline once its chunk is loaded, so no stale teleport can escape this barrier.
            display.instance
                .loadOptionalChunk(position)
                .thenAccept { chunk ->
                    synchronized(lock) {
                        if (!closed && revision == transformRevision) {
                            checkNotNull(chunk) { "Display cannot move into an unloaded chunk" }
                            display.editEntityMeta(BlockDisplayMeta::class.java) {
                                prepared.apply(it, anchor)
                            }
                            display.teleport(position).join()
                        }
                    }
                }
                .exceptionally { error ->
                    MinecraftServer.getExceptionManager().handleException(error)
                    null
                }
        }
    }

    override fun applyViewerState(player: Player, state: SceneViewerVisualState) {
        synchronized(lock) {
            if (!closed) {
                if (state.highlighted) highlighted[player.uuid] = true
                else highlighted.remove(player.uuid)
                sendViewerState(player)
            }
        }
    }

    override fun clearViewerState(player: Player) {
        synchronized(lock) {
            if (!closed) {
                highlighted.remove(player.uuid)
                sendViewerState(player)
            }
        }
    }

    override fun startAnimation(animation: LocalId, elapsedMillis: Long) {
        throw IllegalArgumentException("Placeholder display has no animation '$animation'")
    }

    override fun stopAnimation(animation: LocalId?) = Unit

    override fun advanceAnimation(elapsedMillis: Long) = Unit

    override fun close() {
        synchronized(lock) {
            if (!closed) {
                closed = true
                highlighted.clear()
                display.viewerState = null
                display.remove()
            }
        }
    }

    private fun sendViewerState(player: Player) {
        val base = display.metadataPacket.entries()[0]?.value() as? Byte ?: 0
        val flags =
            if (highlighted[player.uuid] == true) (base.toInt() or 0x40).toByte()
            else (base.toInt() and 0xBF).toByte()
        player.sendPacket(EntityMetaDataPacket(display.entityId, mapOf(0 to Metadata.Byte(flags))))
    }
}
