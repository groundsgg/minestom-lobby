package gg.grounds.minestom.lobby.scene

import gg.grounds.npc.core.model.NpcModel
import gg.grounds.npc.minestom.config.NpcRenderConfig
import gg.grounds.npc.minestom.spi.NpcMeshResolver
import gg.grounds.npc.minestom.spi.NpcSoundSink
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import gg.grounds.scene.npc.minestom.*
import java.util.concurrent.CompletionStage
import net.minestom.server.component.DataComponents
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

internal interface AnimatedSceneRenderers : SceneAssetRendererRegistry {
    fun supportsAnimation(asset: AssetKey, animation: LocalId?): Boolean

    fun supportsViewerScale(asset: AssetKey): Boolean = false
}

/** Grounds composes shared NPC rendering, exact product content and editor placeholders. */
internal class LobbySceneRenderers(
    assets: AssetCatalog,
    models: Map<AssetKey, NpcModel> = GroundsNpcModelSource.load(assets),
) : AnimatedSceneRenderers {
    init {
        GroundsNpcModelSource.validate(assets, models)
    }

    private val placeholders = LobbyPlaceholderRenderers(assets)
    private val effects = LobbySceneEffects(assets)
    private val items =
        models.values
            .flatMap { it.parts }
            .map { it.model }
            .distinct()
            .associateWith { key ->
                ItemStack.of(Material.PAPER).with(DataComponents.ITEM_MODEL, key)
            }
    private val modelSource = SceneNpcModelSource { key -> models[AssetKey(key)] }
    private val meshes = NpcMeshResolver { key ->
        requireNotNull(items[key]) { "Unregistered NPC part $key" }
    }
    private val sounds = NpcSoundSink { instance, origin, sound ->
        effects.playNear(instance, SceneNpcTransformMapper.toScene(origin), sound)
    }
    private val config =
        NpcRenderConfig.builder()
            .idleClip("idle")
            .variationClip("idle2")
            .protectSoundPlayback(true)
            .build()
    private val shared = SceneNpcRenderers(modelSource, meshes, sounds, config)
    // A personal FOLLOW_SHARED channel can inherit samples from a distant shared viewer.
    private val guide =
        SceneNpcRenderers(
            modelSource,
            meshes,
            sounds,
            config
                .toBuilder()
                .nearIntervalMillis(50)
                .midIntervalMillis(50)
                .farIntervalMillis(50)
                .build(),
        )

    override fun rendererFor(asset: AssetKey, kind: AssetKind): SceneAssetRendererFactory? {
        val standard =
            shared.rendererFor(asset, kind) ?: return placeholders.rendererFor(asset, kind)
        if (standard !is ScopedSceneAssetRendererFactory) return standard
        val detailed = guide.rendererFor(asset, kind) as ScopedSceneAssetRendererFactory
        return object : ScopedSceneAssetRendererFactory {
            private fun select(context: SceneAssetRenderContext) =
                if (context.elementId == LocalId("spawn-guide") && context.partId == null) detailed
                else standard

            override fun create(context: SceneAssetRenderContext) = select(context).create(context)

            override fun createScoped(
                context: SceneAssetRenderContext,
                lifetime: SceneRendererLifetime,
            ): CompletionStage<RenderedAssetHandle> =
                select(context).createScoped(context, lifetime)
        }
    }

    override fun supportsAnimation(asset: AssetKey, animation: LocalId?) =
        shared.supportsAnimation(asset, animation)

    override fun supportsViewerScale(asset: AssetKey) = shared.supportsViewerScale(asset)
}
