package gg.grounds.minestom.lobby.scene

import gg.grounds.npc.minestom.nameplate.NpcNameplate
import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.AssetKind
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.minestom.*
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicReference
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.entity.metadata.display.AbstractDisplayMeta
import net.minestom.server.instance.Instance

internal data class StageDialogueLabel(
    val station: LocalId,
    val text: Component,
    val position: Pos,
) {
    private val attached = AtomicReference(emptyList<NpcNameplate>())

    fun riseFor(
        player: Player,
        position: Pos,
        duration: Duration,
    ): gg.grounds.dialogue.minestom.SceneNameplateMotion.Lease? {
        val label = attached.get().getOrNull(1) ?: return null
        val pose = label.poseFor(player, position, durationTicks(duration)) ?: return null
        return object : gg.grounds.dialogue.minestom.SceneNameplateMotion.Lease {
            override fun update(position: Pos, duration: Duration) =
                pose.update(position, durationTicks(duration))

            override fun close() = pose.close()
        }
    }

    private fun durationTicks(duration: Duration): Int =
        kotlin.math.ceil(duration.toNanos() / 50_000_000.0).toInt()

    fun hideFor(player: Player): AutoCloseable? {
        val leases = mutableListOf<AutoCloseable>()
        try {
            val labels = attached.get()
            if (labels.isEmpty()) return null
            for (label in labels) {
                val lease = label.hideFor(player)
                if (lease == null) {
                    leases.asReversed().forEach(AutoCloseable::close)
                    return null
                }
                leases.add(lease)
            }
        } catch (failure: Exception) {
            leases.asReversed().forEach { lease ->
                try {
                    lease.close()
                } catch (cleanupFailure: Exception) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
        return AutoCloseable { leases.asReversed().forEach(AutoCloseable::close) }
    }

    private data class Audience(val hidden: Set<Player>, val packed: Set<Player>)

    private fun display(
        custom: Component?,
        customStyle: Boolean,
        audience: () -> Audience,
    ): NpcNameplate =
        object :
            NpcNameplate(
                if (customStyle) requireNotNull(custom) else text,
                { meta ->
                    if (customStyle) {
                        meta.setBillboardRenderConstraints(
                            AbstractDisplayMeta.BillboardConstraints.CENTER
                        )
                        meta.setBrightness(15, 15)
                        meta.setBackgroundColor(0)
                        meta.setUseDefaultBackground(false)
                        meta.setLineWidth(32_767)
                        meta.setScale(Vec(0.32, 0.32, 0.32))
                        meta.setTranslation(Vec(-0.008, -0.016, 0.0))
                    }
                },
            ) {
            @Volatile private var current = audience()

            init {
                setAudience { player ->
                    val snapshot = current
                    player !in snapshot.hidden && (player in snapshot.packed) == customStyle
                }
            }

            override fun update(time: Long) {
                val next = audience()
                if (current != next) {
                    current = next
                    refreshAudience()
                }
            }
        }

    fun renderers(
        delegate: SceneAssetRendererRegistry,
        body: AssetKey,
        custom: Component? = null,
        packedViewers: () -> Set<Player> = ::emptySet,
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
                        attach(
                            context.instance,
                            lifetime,
                            npcDialogueViewers,
                            custom,
                            packedViewers,
                        )
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
    ): Entity = attach(instance, lifetime, npcDialogueViewers, null, ::emptySet)

    fun attach(
        instance: Instance,
        lifetime: SceneRendererLifetime,
        npcDialogueViewers: () -> Set<Player>,
        custom: Component?,
        packedViewers: () -> Set<Player>,
    ): Entity {
        val audience = {
            Audience(npcDialogueViewers(), if (custom == null) emptySet() else packedViewers())
        }
        val labels = buildList {
            add(display(custom, false, audience))
            if (custom != null) add(display(custom, true, audience))
        }
        val spawned = CompletableFuture<Void>()
        val cleaned = CompletableFuture<Void>()
        lifetime.trackCleanup(cleaned)
        // Wait for any outstanding instance assignment before removing the entity.
        lifetime.onInvalidated {
            attached.compareAndSet(labels, emptyList())
            spawned.whenComplete { _, _ ->
                try {
                    labels.forEach(Entity::remove)
                    cleaned.complete(null)
                } catch (failure: Exception) {
                    cleaned.completeExceptionally(failure)
                }
            }
        }
        try {
            if (
                !lifetime.runIfCurrent {
                    val spawns =
                        labels.map { label ->
                            try {
                                checkNotNull(label.setInstance(instance, position)) {
                                    "Stage dialogue label instance assignment was cancelled"
                                }
                            } catch (failure: Exception) {
                                CompletableFuture.failedFuture(failure)
                            }
                        }
                    CompletableFuture.allOf(*spawns.toTypedArray()).whenComplete { _, failure ->
                        if (failure == null) lifetime.runIfCurrent { attached.set(labels) }
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
        return labels.first()
    }
}
