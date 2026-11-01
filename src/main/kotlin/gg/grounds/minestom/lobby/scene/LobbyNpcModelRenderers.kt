package gg.grounds.minestom.lobby.scene

import gg.grounds.resourcepacks.npc.*
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

internal class LobbyNpcModelRenderers(
    private val assets: AssetCatalog,
    private val models: Map<AssetKey, NpcModel> = NpcModels.load(assets),
) : AnimatedSceneRenderers {
    init {
        models.forEach { (key, model) ->
            val definition =
                requireNotNull(assets.assets[key]) { "NPC model absent from exact catalogue: $key" }
            require(
                definition.kind == AssetKind.NPC_BODY &&
                    definition.defaultBounds == model.bounds &&
                    definition.animations == model.animations.keys.map(::LocalId).toSet()
            ) {
                "NPC model/catalogue contract mismatch: $key"
            }
            require(
                model.sounds.values.all { assets.assets[AssetKey(it.key)]?.kind == AssetKind.SOUND }
            ) {
                "NPC sound missing from catalogue"
            }
        }
    }

    override fun supportsAnimation(asset: AssetKey, animation: LocalId?) =
        models[asset]?.let { animation == null || animation.value in it.animations } == true

    override fun rendererFor(asset: AssetKey, kind: AssetKind): SceneAssetRendererFactory? {
        val model = models[asset]?.takeIf { kind == AssetKind.NPC_BODY } ?: return null
        return SceneAssetRendererFactory { context ->
            val lock = Any()
            val displays =
                model.parts.map { part ->
                    NpcItemDisplay(lock).also { display ->
                        display.editEntityMeta(ItemDisplayMeta::class.java) { meta ->
                            meta.setItemStack(
                                ItemStack.of(Material.PAPER)
                                    .with(DataComponents.ITEM_MODEL, part.model)
                            )
                            meta.setTransformationInterpolationDuration(2)
                        }
                    }
                }
            val result = CompletableFuture<RenderedAssetHandle>()
            val anchor = context.transform.root.position
            val attached =
                displays.map { display ->
                    try {
                        display.setInstance(context.instance, Pos(anchor.x, anchor.y, anchor.z))
                            ?: CompletableFuture.failedFuture(
                                IllegalStateException("NPC attachment cancelled")
                            )
                    } catch (failure: Throwable) {
                        CompletableFuture.failedFuture<Void>(failure)
                    }
                }
            CompletableFuture.allOf(*attached.toTypedArray()).whenComplete { _, failure ->
                if (
                    failure != null ||
                        displays.any {
                            !it.isActive ||
                                it.isRemoved ||
                                it.instance !== context.instance ||
                                it.chunk == null ||
                                context.instance.getEntityById(it.entityId) !== it
                        }
                ) {
                    displays.forEach(Entity::remove)
                    result.completeExceptionally(
                        failure ?: IllegalStateException("NPC part attachment failed")
                    )
                } else {
                    try {
                        val handle =
                            AnimatedNpcHandle(
                                lock,
                                model,
                                displays,
                                context.transform,
                                LobbySceneEffects(assets),
                            )
                        if (!result.complete(handle)) handle.close()
                    } catch (error: Throwable) {
                        displays.forEach(Entity::remove)
                        result.completeExceptionally(error)
                    }
                }
            }
            result
        }
    }
}

private class AnimatedNpcHandle(
    private val lock: Any,
    private val model: NpcModel,
    private val displays: List<NpcItemDisplay>,
    private var transform: SceneRenderTransform,
    private val effects: LobbySceneEffects,
) : RenderedAssetHandle {
    private var closed = false
    private var revision = 0L
    private var requested: String? = null
    private var elapsed = 0L
    private var lastRendered = Long.MIN_VALUE
    private var variationStarted: Long? = null
    private var nextVariation = 20_000L
    private val highlighted = mutableSetOf<UUID>()
    private val viewerScales = mutableMapOf<UUID, Double>()

    init {
        displays.forEach { display ->
            display.viewerState = { player -> sendViewerState(display, player) }
            display.visualState = { player ->
                (viewerScales[player.uuid] ?: 1.0) to (player.uuid in highlighted)
            }
        }
        render(null, 0.0)
    }

    override fun applyTransform(transform: SceneRenderTransform) {
        synchronized(lock) {
            if (closed) return
            val token = ++revision
            val anchor = transform.root.position
            val instance = displays.first().instance
            instance
                .loadOptionalChunk(Pos(anchor.x, anchor.y, anchor.z))
                .thenAccept { chunk ->
                    synchronized(lock) {
                        if (!closed && token == revision) {
                            checkNotNull(chunk)
                            this.transform = transform
                            displays.forEach {
                                it.teleport(Pos(anchor.x, anchor.y, anchor.z)).join()
                            }
                            advance(elapsed, true)
                        }
                    }
                }
                .exceptionally { error ->
                    net.minestom.server.MinecraftServer.getExceptionManager().handleException(error)
                    null
                }
        }
    }

    override fun startAnimation(animation: LocalId, elapsedMillis: Long) {
        synchronized(lock) {
            if (closed) return
            val clip =
                requireNotNull(model.animations[animation.value]) { "Unknown NPC clip $animation" }
            requested = animation.value
            elapsed = elapsedMillis
            variationStarted = null
            nextVariation = elapsedMillis + 20_000
            if (elapsedMillis == 0L) playSound(animation.value)
            advance(elapsedMillis, true)
        }
    }

    override fun stopAnimation(animation: LocalId?) {
        synchronized(lock) {
            if (closed || (animation != null && requested != animation.value)) return
            requested = null
            variationStarted = null
            render(null, 0.0)
        }
    }

    override fun advanceAnimation(elapsedMillis: Long) {
        synchronized(lock) { if (!closed) advance(elapsedMillis, false) }
    }

    private fun advance(time: Long, force: Boolean) {
        elapsed = time
        if (
            !force &&
                lastRendered != Long.MIN_VALUE &&
                time >= lastRendered &&
                time - lastRendered < 100
        )
            return
        lastRendered = time
        val name = requested
        val clip = name?.let(model.animations::get)
        var displayed = name
        var seconds = time / 1000.0
        if (
            clip != null &&
                clip.loop == "once" &&
                seconds >= clip.length &&
                "idle" in model.animations
        ) {
            displayed = "idle"
            seconds -= clip.length
        }
        if (displayed == "idle" && "idle2" in model.animations) {
            val variation = variationStarted
            if (variation != null) {
                val age = (time - variation) / 1000.0
                if (age < model.animations.getValue("idle2").length) {
                    displayed = "idle2"
                    seconds = age
                } else {
                    variationStarted = null
                    nextVariation = time + 20_000
                }
            } else if (time >= nextVariation) {
                nextVariation = time + 20_000
                val anchor = transform.root.position
                val near =
                    displays.first().instance.players.any { p ->
                        val pos = p.position
                        (pos.x() - anchor.x) * (pos.x() - anchor.x) +
                            (pos.y() - anchor.y) * (pos.y() - anchor.y) +
                            (pos.z() - anchor.z) * (pos.z() - anchor.z) <= 25
                    }
                if (!near) {
                    variationStarted = time
                    displayed = "idle2"
                    seconds = 0.0
                    playSound("idle2")
                }
            }
        }
        render(displayed, seconds)
    }

    private fun playSound(name: String) {
        model.sounds[name]?.let {
            effects.playNear(displays.first().instance, transform.root.position, it)
        }
    }

    private fun render(name: String?, seconds: Double) {
        val facing = NpcMatrix.rotation(Vec3(0.0, 180.0, 0.0))
        val pose = model.itemDisplayPose(name, seconds)
        displays.forEachIndexed { index, display ->
            display.editEntityMeta(ItemDisplayMeta::class.java) { meta ->
                if (
                    DisplayTransform.fromModel(transform, facing * pose[index])
                        .apply(meta, transform.root.position)
                ) {
                    meta.setTransformationInterpolationStartDelta(0)
                }
            }
        }
    }

    override fun applyViewerState(player: Player, state: SceneViewerVisualState) {
        synchronized(lock) {
            if (closed) return
            val previous = viewerScales[player.uuid] ?: 1.0
            if (state.scaleMultiplier == 1.0) viewerScales.remove(player.uuid)
            else viewerScales[player.uuid] = state.scaleMultiplier
            if (state.highlighted) highlighted.add(player.uuid) else highlighted.remove(player.uuid)
            displays.forEach { it.sendVisualState(player, previous != state.scaleMultiplier) }
        }
    }

    override fun clearViewerState(player: Player) {
        synchronized(lock) {
            if (!closed) {
                highlighted.remove(player.uuid)
                val changed = viewerScales.remove(player.uuid) != null
                displays.forEach { it.sendVisualState(player, changed) }
            }
        }
    }

    private fun sendViewerState(display: NpcItemDisplay, player: Player) {
        if (closed) return
        display.sendVisualState(player, false)
    }

    override fun close() {
        synchronized(lock) {
            if (!closed) {
                closed = true
                revision++
                highlighted.clear()
                viewerScales.clear()
                displays.forEach {
                    it.viewerState = null
                    it.visualState = null
                    it.remove()
                }
            }
        }
    }
}
