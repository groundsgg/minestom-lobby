package gg.grounds.minestom.lobby.scene

import gg.grounds.npc.core.NpcModel
import gg.grounds.npc.minestom.NpcMeshResolver
import gg.grounds.npc.minestom.NpcRenderConfig
import gg.grounds.npc.minestom.NpcSoundSink
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import gg.grounds.scene.npc.minestom.*
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
    private val shared =
        SceneNpcRenderers(
            SceneNpcModelSource { key -> models[AssetKey(key)] },
            NpcMeshResolver { key -> requireNotNull(items[key]) { "Unregistered NPC part $key" } },
            NpcSoundSink { instance, origin, sound ->
                effects.playNear(instance, SceneNpcTransformMapper.toScene(origin), sound)
            },
            NpcRenderConfig(idleClip = "idle", variationClip = "idle2"),
        )

    override fun rendererFor(asset: AssetKey, kind: AssetKind) =
        shared.rendererFor(asset, kind) ?: placeholders.rendererFor(asset, kind)

    override fun supportsAnimation(asset: AssetKey, animation: LocalId?) =
        shared.supportsAnimation(asset, animation)

    override fun supportsViewerScale(asset: AssetKey) = shared.supportsViewerScale(asset)
}
