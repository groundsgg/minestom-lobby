package gg.grounds.minestom.lobby.scene

import gg.grounds.npc.minestom.presentation.*
import gg.grounds.scene.format.AssetKind
import gg.grounds.scene.minestom.*
import gg.grounds.scene.npc.minestom.PersonalNpcRenderedAssetHandle
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Captures only the exact Guide renderer generation; Scene continues to own the shared NPC. */
internal class GuideNpcPresentations(
    private val elements: Set<gg.grounds.scene.format.LocalId> =
        setOf(gg.grounds.scene.format.LocalId("spawn-guide"))
) {
    private class Entry(
        val handle: PersonalNpcRenderedAssetHandle,
        val lifetime: SceneRendererLifetime,
    ) {
        val starts = mutableSetOf<NpcPresentationStart>()
    }

    private val lock = Any()
    private val current = mutableMapOf<gg.grounds.scene.format.LocalId, Entry>()

    fun renderers(delegate: SceneAssetRendererRegistry) =
        SceneAssetRendererRegistry { asset, kind ->
            val factory = delegate.rendererFor(asset, kind)
            if (factory !is ScopedSceneAssetRendererFactory || kind != AssetKind.NPC_BODY) factory
            else
                object : ScopedSceneAssetRendererFactory {
                    override fun create(context: SceneAssetRenderContext) = factory.create(context)

                    override fun createScoped(
                        context: SceneAssetRenderContext,
                        lifetime: SceneRendererLifetime,
                    ): CompletionStage<RenderedAssetHandle> {
                        val result = factory.createScoped(context, lifetime)
                        return if (context.elementId !in elements || context.partId != null) result
                        else
                            result.thenApply { handle ->
                                if (handle is PersonalNpcRenderedAssetHandle)
                                    capture(context.elementId, handle, lifetime)
                                handle
                            }
                    }
                }
        }

    private fun capture(
        elementId: gg.grounds.scene.format.LocalId,
        handle: PersonalNpcRenderedAssetHandle,
        lifetime: SceneRendererLifetime,
    ) {
        val entry = Entry(handle, lifetime)
        val cleanup = CompletableFuture<Void>()
        lifetime.trackCleanup(cleanup)
        lifetime.onInvalidated {
            val starts =
                synchronized(lock) {
                    if (current[elementId] === entry) current.remove(elementId)
                    entry.starts.toList().also { entry.starts.clear() }
                }
            val futures =
                starts.map { start ->
                    try {
                        start.cancel().toCompletableFuture()
                    } catch (_: Exception) {
                        CompletableFuture.completedFuture<Void>(null)
                    }
                }
            CompletableFuture.allOf(*futures.toTypedArray()).whenComplete { _, _ ->
                cleanup.complete(null)
            }
        }
        lifetime.runIfCurrent { synchronized(lock) { current[elementId] = entry } }
    }

    fun prepare(
        elementId: gg.grounds.scene.format.LocalId,
        options: NpcPresentationOptions,
    ): NpcPresentationStart? {
        val entry = synchronized(lock) { current[elementId] } ?: return null
        var prepared: NpcPresentationStart? = null
        entry.lifetime.runIfCurrent {
            val start = entry.handle.preparePresentation(options)
            prepared = start
            synchronized(lock) { entry.starts.add(start) }
            start.ready().whenComplete { result, failure ->
                if (result is NpcPresentationResult.Ready)
                    result.handle.closed().whenComplete { _, _ ->
                        synchronized(lock) { entry.starts.remove(start) }
                    }
                else if (failure != null || result is NpcPresentationResult.Rejected)
                    synchronized(lock) { entry.starts.remove(start) }
            }
        }
        return prepared
    }
}
