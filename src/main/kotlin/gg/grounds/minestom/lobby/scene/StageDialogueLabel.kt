package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.AssetKind
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.minestom.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.entity.metadata.display.TextDisplayMeta
import net.minestom.server.instance.Instance

internal data class StageDialogueLabel(
    val station: LocalId,
    val text: Component,
    val position: Pos,
) {
    fun renderers(
        delegate: SceneAssetRendererRegistry,
        body: AssetKey,
        npcDialogueViewers: () -> Set<Player>,
    ) = SceneAssetRendererRegistry { asset, kind ->
        val original = delegate.rendererFor(asset, kind)
        if (original == null || asset != body || kind != AssetKind.NPC_BODY) original
        else
            object : ScopedSceneAssetRendererFactory {
                override fun create(context: SceneAssetRenderContext) = original.create(context)

                override fun createScoped(
                    context: SceneAssetRenderContext,
                    lifetime: SceneRendererLifetime,
                ): CompletionStage<RenderedAssetHandle> {
                    if (context.elementId == station && context.partId == null)
                        attach(context.instance, lifetime, npcDialogueViewers)
                    return if (original is ScopedSceneAssetRendererFactory)
                        original.createScoped(context, lifetime)
                    else original.create(context)
                }
            }
    }

    fun attach(
        instance: Instance,
        lifetime: SceneRendererLifetime,
        npcDialogueViewers: () -> Set<Player>,
    ): Entity {
        val label =
            object : Entity(EntityType.TEXT_DISPLAY) {
                @Volatile private var hidden = npcDialogueViewers()

                init {
                    setNoGravity(true)
                    (entityMeta as TextDisplayMeta).text = text
                    updateViewableRule { it !in hidden }
                }

                override fun update(time: Long) {
                    val current = npcDialogueViewers()
                    if (hidden != current) {
                        hidden = current
                        updateViewableRule()
                    }
                }
            }
        val spawned = CompletableFuture<Void>()
        val cleaned = CompletableFuture<Void>()
        lifetime.trackCleanup(cleaned)
        // Wait for any outstanding instance assignment before removing the entity.
        lifetime.onInvalidated {
            spawned.whenComplete { _, _ ->
                try {
                    label.remove()
                    cleaned.complete(null)
                } catch (failure: Exception) {
                    cleaned.completeExceptionally(failure)
                }
            }
        }
        try {
            if (
                !lifetime.runIfCurrent {
                    val spawn =
                        checkNotNull(label.setInstance(instance, position)) {
                            "Stage dialogue label instance assignment was cancelled"
                        }
                    spawn.whenComplete { _, failure ->
                        spawned.complete(null)
                        if (failure != null) lifetime.reportFailure(failure)
                    }
                }
            )
                spawned.complete(null)
        } catch (failure: Exception) {
            spawned.complete(null)
            lifetime.reportFailure(failure)
        }
        return label
    }
}
