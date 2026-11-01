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
    private val metrics = displays.first().renderMetrics
    private var closed = false
    private var revision = 0L
    private var requested: String? = null
    private var elapsed = 0L
    private var lastRendered = Long.MIN_VALUE
    private var variationStarted: Long? = null
    private var nextVariation = 20_000L
    private val viewerVisuals = mutableMapOf<UUID, Pair<Double, Boolean>>()
    private var viewerIntervals = emptyMap<UUID, Long>()
    private val nextViewerUpdate = mutableMapOf<Long, Long>()
    private var renderInterval = 500L
    private var headLook = Vec3(0.0, 0.0, 0.0)
    private var pendingHeadLook = headLook
    private var previousPose: List<NpcMatrix>? = null
    private var previousTransform: SceneRenderTransform? = null
    private lateinit var displayMapper: (NpcMatrix) -> DisplayTransform

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

    private fun refreshViewers() =
        measured(NpcRenderPhase.VISIBILITY) {
            val instance = displays.first().instance
            val anchor = transform.root.position
            val next = buildMap {
                instance.players.forEach { player ->
                    val distance = NpcRenderPolicy.distanceSquared(player, instance, anchor)
                    if (
                        NpcRenderPolicy.canView(
                            player,
                            instance,
                            anchor,
                            player.uuid in viewerIntervals,
                        )
                    ) {
                        put(
                            player.uuid,
                            NpcRenderPolicy.viewerIntervalMillis(
                                distance,
                                viewerIntervals[player.uuid],
                            ),
                        )
                    }
                }
            }
            renderInterval = next.values.minOrNull() ?: 500L
            if (next == viewerIntervals) return
            val previous = viewerIntervals
            val resuming = previous.isEmpty() && next.isNotEmpty()
            viewerIntervals = next
            val intervals = next.values.toSet()
            nextViewerUpdate.keys.retainAll(intervals)
            intervals.forEach { nextViewerUpdate.putIfAbsent(it, elapsed + it) }
            displays.forEach { it.configureUpdates(intervals) }
            // Refresh the shared target before snapshots without forcing unrelated cohorts to send.
            if (resuming || next.any { (id, interval) -> previous[id] != interval })
                advance(elapsed, true, false, false)
            displays.forEach { display ->
                val previousViewers = display.viewers.toList()
                display.updateViewableRule { player ->
                    player.instance === instance && player.uuid in viewerIntervals
                }
                previousViewers.forEach { player ->
                    val interval = next[player.uuid]
                    if (
                        interval != null &&
                            previous[player.uuid] != interval &&
                            player in display.viewers
                    )
                        display.sendPoseState(player, interval)
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
            flushViewerUpdates(elapsed, true)
        }
    }

    override fun advanceAnimation(elapsedMillis: Long) {
        synchronized(lock) { if (!closed) advance(elapsedMillis, false) }
    }

    private fun advance(
        time: Long,
        force: Boolean,
        updateViewers: Boolean = true,
        flush: Boolean = true,
    ) {
        val seeking = time < elapsed
        elapsed = time
        if (updateViewers) refreshViewers()
        if (viewerIntervals.isEmpty()) return
        val interval = renderInterval
        if (
            !force &&
                lastRendered != Long.MIN_VALUE &&
                time >= lastRendered &&
                time - lastRendered < interval
        ) {
            if (flush) flushViewerUpdates(time, seeking)
            return
        }
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
        if (flush) flushViewerUpdates(time, force || seeking)
    }

    private fun flushViewerUpdates(time: Long, force: Boolean) {
        if (nextViewerUpdate.values.none { force || time >= it }) return
        measured(NpcRenderPhase.DELIVERY) {
            for ((interval, deadline) in nextViewerUpdate) {
                if (!force && time < deadline) continue
                displays.forEach { display -> display.flushUpdates(interval, viewerIntervals) }
                nextViewerUpdate[interval] = time + interval
            }
        }
    }

    private inline fun <T> measured(phase: NpcRenderPhase, action: () -> T): T {
        val metrics = metrics ?: return action()
        val started = System.nanoTime()
        try {
            return action()
        } finally {
            metrics.finish(phase, started)
        }
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
        val pose = measured(NpcRenderPhase.POSE) { model.itemDisplayPose(name, seconds, headLook) }
        measured(NpcRenderPhase.TRANSFORM) {
            val unchangedRoot = previousTransform == transform
            if (!unchangedRoot) displayMapper = DisplayTransform.prepareModel(transform)
            val previous = previousPose
            val duration = (renderInterval / 50).toInt()
            displays.forEachIndexed { index, display ->
                if (
                    unchangedRoot &&
                        previous != null &&
                        previous[index].values.contentEquals(pose[index].values)
                )
                    return@forEachIndexed
                display.editEntityMeta(ItemDisplayMeta::class.java) { meta ->
                    if (
                        displayMapper(NPC_FACING * pose[index]).apply(meta, transform.root.position)
                    ) {
                        meta.setTransformationInterpolationDuration(duration)
                        meta.setTransformationInterpolationStartDelta(0)
                    }
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
                    if (previous != state.scaleMultiplier)
                        it.sendPoseState(player, viewerIntervals[player.uuid] ?: renderInterval)
                    else it.sendVisualFlags(player)
            }
        }
    }

    override fun clearViewerState(player: Player) {
        synchronized(lock) {
            if (!closed) {
                val changed = viewerVisuals.remove(player.uuid)?.first?.let { it != 1.0 } ?: false
                displays.forEach {
                    if (player in it.viewers) {
                        if (changed)
                            it.sendPoseState(player, viewerIntervals[player.uuid] ?: renderInterval)
                        else it.sendVisualFlags(player)
                    }
                }
            }
        }
    }

    private fun sendViewerState(display: NpcItemDisplay, player: Player) {
        if (closed) return
        display.sendPoseState(player, viewerIntervals[player.uuid] ?: renderInterval)
    }

    override fun close() {
        synchronized(lock) {
            if (!closed) {
                closed = true
                revision++
                visibilityTask.cancel()
                viewerVisuals.clear()
                viewerIntervals = emptyMap()
                nextViewerUpdate.clear()
                displays.forEach {
                    it.viewerState = null
                    it.visualState = null
                    it.clearUpdates()
                    it.remove()
                }
            }
        }
    }
}

private val NPC_FACING = NpcMatrix.rotation(Vec3(0.0, 180.0, 0.0))
