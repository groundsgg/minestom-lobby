package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.AssetKind
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.minestom.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
    private val attached = AtomicReference<LabelEntity?>()

    fun hideFor(player: Player): AutoCloseable? = attached.get()?.hideFor(player)

    private class LabelEntity(text: Component, private val npcDialogueViewers: () -> Set<Player>) :
        Entity(EntityType.TEXT_DISPLAY) {
        @Volatile private var hidden = npcDialogueViewers()
        @Volatile private var leased = emptySet<Player>()

        init {
            setNoGravity(true)
            (entityMeta as TextDisplayMeta).text = text
            updateViewableRule { it !in hidden && it !in leased }
        }

        override fun update(time: Long) {
            val current = npcDialogueViewers()
            if (hidden != current) {
                hidden = current
                updateViewableRule()
            }
        }

        @Synchronized
        fun hideFor(player: Player): AutoCloseable? {
            if (isRemoved || player.instance !== instance || !player.isOnline || player in leased)
                return null
            leased = leased + player
            try {
                updateViewableRule()
            } catch (failure: Exception) {
                leased = leased - player
                throw failure
            }
            val closed = AtomicBoolean()
            return AutoCloseable { if (closed.compareAndSet(false, true)) restoreFor(player) }
        }

        @Synchronized
        private fun restoreFor(player: Player) {
            leased = leased - player
            if (!isRemoved && player.instance === instance && player.isOnline) updateViewableRule()
        }
    }

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
        val label = LabelEntity(text, npcDialogueViewers)
        val spawned = CompletableFuture<Void>()
        val cleaned = CompletableFuture<Void>()
        lifetime.trackCleanup(cleaned)
        // Wait for any outstanding instance assignment before removing the entity.
        lifetime.onInvalidated {
            attached.compareAndSet(label, null)
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
                        if (failure == null) lifetime.runIfCurrent { attached.set(label) }
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
