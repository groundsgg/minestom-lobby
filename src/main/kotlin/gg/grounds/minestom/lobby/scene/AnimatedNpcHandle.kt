package gg.grounds.minestom.lobby.scene

import gg.grounds.resourcepacks.npc.*
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import java.util.UUID
import kotlin.math.abs
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import net.minestom.server.timer.TaskSchedule

internal class AnimatedNpcHandle(
    private val lock: Any,
    private val model: NpcModel,
    private val displays: List<NpcItemDisplay>,
    private var transform: SceneRenderTransform,
    private val effects: LobbySceneEffects,
) : HeadLookRenderedAssetHandle {
    private var closed = false
    private var revision = 0L
    private var requested: String? = null
    private var elapsed = 0L
    private var lastRendered = Long.MIN_VALUE
    private var variationStarted: Long? = null
    private var nextVariation = 20_000L
    private val viewerVisuals = mutableMapOf<UUID, Pair<Double, Boolean>>()
    private var visiblePlayers = emptySet<UUID>()
    private var nearestViewerSquared = Double.POSITIVE_INFINITY
    private var headLook = Vec3(0.0, 0.0, 0.0)
    private var pendingHeadLook = headLook
    private var previousPose: List<NpcMatrix>? = null
    private var previousTransform: SceneRenderTransform? = null

    override val headLookOrigin: Vec3?
        get() =
            model.headBone?.let { bone ->
                // Imported bones face -Z; the model-facing correction makes Scene forward +Z.
                Vec3(-bone.origin.x, bone.origin.y + 0.15, -bone.origin.z)
            }

    override fun applyHeadLook(yawDegrees: Double, pitchDegrees: Double) {
        synchronized(lock) {
            if (closed) return
            // Conjugating by the model's Y180 facing correction reverses the pitch axis.
            val next = Vec3(-pitchDegrees, yawDegrees, 0.0)
            if (pendingHeadLook == next) return
            pendingHeadLook = next
            if (requested == null) advance(elapsed, true)
        }
    }

    init {
        displays.forEach { display ->
            display.viewerState = { player -> sendViewerState(display, player) }
            display.visualState = { player -> viewerVisuals[player.uuid] ?: DEFAULT_NPC_VISUAL }
        }
        render(null, 0.0)
        refreshViewers()
    }

    private val visibilityTask =
        displays
            .first()
            .instance
            .scheduler()
            .buildTask { synchronized(lock) { if (!closed) refreshViewers() } }
            .repeat(TaskSchedule.tick(4))
            .schedule()

    private fun refreshViewers() {
        val instance = displays.first().instance
        val anchor = transform.root.position
        var nearest = Double.POSITIVE_INFINITY
        val next = buildSet {
            instance.players.forEach { player ->
                val distance = NpcRenderPolicy.distanceSquared(player, instance, anchor)
                if (
                    NpcRenderPolicy.canView(player, instance, anchor, player.uuid in visiblePlayers)
                ) {
                    add(player.uuid)
                    nearest = minOf(nearest, distance)
                }
            }
        }
        nearestViewerSquared = nearest
        if (next == visiblePlayers) return
        val resuming = visiblePlayers.isEmpty() && next.isNotEmpty()
        visiblePlayers = next
        if (resuming) advance(elapsed, true, false)
        displays.forEach { display ->
            display.updateViewableRule { player ->
                player.instance === instance && player.uuid in visiblePlayers
            }
        }
    }

    override fun applyTransform(transform: SceneRenderTransform) {
        synchronized(lock) {
            if (closed) return
            val token = ++revision
            val anchor = transform.root.position
            if (anchor == this.transform.root.position) {
                this.transform = transform
                if (requested == null) advance(elapsed, true)
                return
            }
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
            commitHeadLook()
            render(null, 0.0)
        }
    }

    override fun advanceAnimation(elapsedMillis: Long) {
        synchronized(lock) { if (!closed) advance(elapsedMillis, false) }
    }

    private fun advance(time: Long, force: Boolean, updateViewers: Boolean = true) {
        elapsed = time
        if (updateViewers) refreshViewers()
        if (visiblePlayers.isEmpty()) return
        val interval = NpcRenderPolicy.updateIntervalMillis(nearestViewerSquared)
        if (
            !force &&
                lastRendered != Long.MIN_VALUE &&
                time >= lastRendered &&
                time - lastRendered < interval
        )
            return
        lastRendered = time
        commitHeadLook()
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

    private fun commitHeadLook() {
        if (
            abs(pendingHeadLook.x - headLook.x) >= 2.0 || abs(pendingHeadLook.y - headLook.y) >= 2.0
        )
            headLook = pendingHeadLook
    }

    private fun render(name: String?, seconds: Double) {
        val pose = model.itemDisplayPose(name, seconds, headLook)
        val unchangedRoot = previousTransform == transform
        val previous = previousPose
        val duration = (NpcRenderPolicy.updateIntervalMillis(nearestViewerSquared) / 50).toInt()
        displays.forEachIndexed { index, display ->
            if (
                unchangedRoot &&
                    previous != null &&
                    previous[index].values.contentEquals(pose[index].values)
            )
                return@forEachIndexed
            display.editEntityMeta(ItemDisplayMeta::class.java) { meta ->
                if (
                    DisplayTransform.fromModel(transform, NPC_FACING * pose[index])
                        .apply(meta, transform.root.position)
                ) {
                    meta.setTransformationInterpolationDuration(duration)
                    meta.setTransformationInterpolationStartDelta(0)
                }
            }
        }
        previousPose = pose
        previousTransform = transform
    }

    override fun applyViewerState(player: Player, state: SceneViewerVisualState) {
        synchronized(lock) {
            if (closed) return
            val previous = viewerVisuals[player.uuid]?.first ?: 1.0
            if (state.scaleMultiplier == 1.0 && !state.highlighted)
                viewerVisuals.remove(player.uuid)
            else viewerVisuals[player.uuid] = state.scaleMultiplier to state.highlighted
            refreshViewers()
            displays.forEach {
                if (player in it.viewers)
                    it.sendVisualState(player, previous != state.scaleMultiplier)
            }
        }
    }

    override fun clearViewerState(player: Player) {
        synchronized(lock) {
            if (!closed) {
                val changed = viewerVisuals.remove(player.uuid)?.first?.let { it != 1.0 } ?: false
                displays.forEach { if (player in it.viewers) it.sendVisualState(player, changed) }
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
                visibilityTask.cancel()
                viewerVisuals.clear()
                visiblePlayers = emptySet()
                displays.forEach {
                    it.viewerState = null
                    it.visualState = null
                    it.remove()
                }
            }
        }
    }
}

private val NPC_FACING = NpcMatrix.rotation(Vec3(0.0, 180.0, 0.0))
