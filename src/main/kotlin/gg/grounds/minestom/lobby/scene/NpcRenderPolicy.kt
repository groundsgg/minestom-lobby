package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.Vec3
import kotlin.math.abs
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance

/** Shared admission rule for initial attachment and subsequent viewer movement. */
internal object NpcRenderPolicy {
    const val VIEW_DISTANCE_SQUARED = 64.0 * 64.0
    private const val RETENTION_DISTANCE_SQUARED = 72.0 * 72.0
    private const val VERTICAL_DISTANCE = 32.0

    fun canView(
        player: Player,
        instance: Instance,
        anchor: Vec3,
        wasVisible: Boolean = false,
    ): Boolean =
        distanceSquared(player, instance, anchor) <=
            (if (wasVisible) RETENTION_DISTANCE_SQUARED else VIEW_DISTANCE_SQUARED) &&
            abs(player.position.y() - anchor.y) <= VERTICAL_DISTANCE

    fun updateIntervalMillis(distanceSquared: Double): Long =
        when {
            distanceSquared <= 16.0 * 16.0 -> 200L
            distanceSquared <= 32.0 * 32.0 -> 250L
            else -> 500L
        }

    fun distanceSquared(player: Player, instance: Instance, anchor: Vec3): Double {
        if (player.instance !== instance) return Double.POSITIVE_INFINITY
        val p = player.position
        val dx = p.x() - anchor.x
        val dy = p.y() - anchor.y
        val dz = p.z() - anchor.z
        return dx * dx + dy * dy + dz * dz
    }
}
