package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.AssetCatalog
import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.AssetKind
import gg.grounds.scene.minestom.SceneAssetRenderContext
import gg.grounds.scene.minestom.SceneAssetRendererFactory
import gg.grounds.scene.minestom.SceneAssetRendererRegistry
import java.util.concurrent.CompletableFuture
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.metadata.display.BlockDisplayMeta
import net.minestom.server.instance.block.Block

internal class LobbyPlaceholderRenderers(private val assets: AssetCatalog) :
    SceneAssetRendererRegistry {
    private val supported =
        mapOf(
            (AssetKey("grounds:editor/guide") to AssetKind.NPC_BODY) to Block.LAPIS_BLOCK,
            (AssetKey("grounds:editor/marker") to AssetKind.PROP) to Block.REDSTONE_BLOCK,
        )

    override fun rendererFor(asset: AssetKey, kind: AssetKind): SceneAssetRendererFactory? {
        val block = supported[asset to kind] ?: return null
        val bounds =
            assets.assets[asset]?.defaultBounds
                ?: throw IllegalArgumentException(
                    "Placeholder asset $asset requires catalog bounds"
                )
        return SceneAssetRendererFactory { context -> create(context, block, bounds) }
    }

    private fun create(
        context: SceneAssetRenderContext,
        block: Block,
        bounds: gg.grounds.scene.format.LocalBounds,
    ) =
        CompletableFuture<gg.grounds.scene.minestom.RenderedAssetHandle>().also { future ->
            val display = HighlightableBlockDisplay()
            val anchor = context.transform.root.position
            display.editEntityMeta(BlockDisplayMeta::class.java) { meta ->
                meta.setBlockState(block)
                DisplayTransform.from(context.transform, bounds).apply(meta, anchor)
            }
            try {
                val attached =
                    display.setInstance(context.instance, Pos(anchor.x, anchor.y, anchor.z))
                if (attached == null) {
                    display.remove()
                    future.completeExceptionally(
                        IllegalStateException("Display attachment was cancelled")
                    )
                } else
                    attached.whenComplete { _, error ->
                        if (
                            error != null ||
                                !display.isActive ||
                                display.isRemoved ||
                                display.instance !== context.instance ||
                                display.chunk == null ||
                                context.instance.getEntityById(display.entityId) !== display
                        ) {
                            display.remove()
                            future.completeExceptionally(
                                error ?: IllegalStateException("Display was not attached")
                            )
                        } else {
                            val handle = PlaceholderDisplayHandle(display, bounds)
                            // A cancelled caller cannot take ownership. Wait until attachment has
                            // settled before removing: Minestom can still register a pending
                            // entity.
                            if (!future.complete(handle)) handle.close()
                        }
                    }
            } catch (error: Throwable) {
                display.remove()
                future.completeExceptionally(error)
            }
        }
}

internal class HighlightableBlockDisplay : Entity(EntityType.BLOCK_DISPLAY) {
    // Never detach this monitor: tracking callbacks can already be waiting when close runs.
    val viewerLock = Any()
    var viewerState: ((net.minestom.server.entity.Player) -> Unit)? = null

    override fun updateNewViewer(player: net.minestom.server.entity.Player) {
        synchronized(viewerLock) {
            if (!isRemoved) {
                super.updateNewViewer(player)
                viewerState?.invoke(player)
            }
        }
    }
}
