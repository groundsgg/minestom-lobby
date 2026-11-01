package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*

internal interface AnimatedSceneRenderers : SceneAssetRendererRegistry {
    fun supportsAnimation(asset: AssetKey, animation: LocalId?): Boolean

    fun supportsViewerScale(asset: AssetKey): Boolean = false
}

/** Exact registered renderers: legacy editor placeholders plus compiled purchased NPCs. */
internal class LobbySceneRenderers(assets: AssetCatalog) : AnimatedSceneRenderers {
    private val placeholders = LobbyPlaceholderRenderers(assets)
    private val models = LobbyNpcModelRenderers(assets)

    override fun rendererFor(asset: AssetKey, kind: AssetKind) =
        models.rendererFor(asset, kind) ?: placeholders.rendererFor(asset, kind)

    override fun supportsAnimation(asset: AssetKey, animation: LocalId?) =
        models.supportsAnimation(asset, animation)

    override fun supportsViewerScale(asset: AssetKey) = models.supportsAnimation(asset, null)
}
