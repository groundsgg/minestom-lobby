package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.LocalBounds
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.minestom.RenderedAssetHandle
import gg.grounds.scene.minestom.SceneRenderTransform
import gg.grounds.scene.minestom.SceneViewerVisualState
import java.util.UUID
import net.minestom.server.entity.Metadata
import net.minestom.server.entity.Player
import net.minestom.server.entity.metadata.display.BlockDisplayMeta
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket

internal class PlaceholderDisplayHandle(
    private val display: HighlightableBlockDisplay,
    private val bounds: LocalBounds,
) : RenderedAssetHandle {
    private val highlighted = mutableMapOf<UUID, Boolean>()

    init {
        display.viewerState = ::sendViewerState
    }

    override fun applyTransform(transform: SceneRenderTransform) {
        display.editEntityMeta(BlockDisplayMeta::class.java) {
            DisplayTransform.from(transform, bounds).apply(it)
        }
    }

    override fun applyViewerState(player: Player, state: SceneViewerVisualState) {
        if (state.highlighted) highlighted[player.uuid] = true else highlighted.remove(player.uuid)
        sendViewerState(player)
    }

    override fun clearViewerState(player: Player) {
        highlighted.remove(player.uuid)
        sendViewerState(player)
    }

    override fun startAnimation(animation: LocalId, elapsedMillis: Long) {
        throw IllegalArgumentException("Placeholder display has no animation '$animation'")
    }

    override fun stopAnimation(animation: LocalId?) = Unit

    override fun advanceAnimation(elapsedMillis: Long) = Unit

    override fun close() {
        highlighted.clear()
        display.viewerState = null
        display.remove()
    }

    private fun sendViewerState(player: Player) {
        val base = display.metadataPacket.entries()[0]?.value() as? Byte ?: 0
        val flags =
            if (highlighted[player.uuid] == true) (base.toInt() or 0x40).toByte()
            else (base.toInt() and 0xBF).toByte()
        player.sendPacket(EntityMetaDataPacket(display.entityId, mapOf(0 to Metadata.Byte(flags))))
    }
}
