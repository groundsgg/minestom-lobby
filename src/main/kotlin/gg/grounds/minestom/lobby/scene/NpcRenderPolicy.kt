package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.Vec3
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance

/** Shared admission rule for initial attachment and subsequent viewer movement. */
internal object NpcRenderPolicy {
    const val VIEW_DISTANCE_SQUARED = 32.0 * 32.0

    fun distanceSquared(player: Player, instance: Instance, anchor: Vec3): Double {
        if (player.instance !== instance) return Double.POSITIVE_INFINITY
        val p = player.position
        val dx = p.x() - anchor.x
        val dy = p.y() - anchor.y
        val dz = p.z() - anchor.z
        return dx * dx + dy * dy + dz * dz
    }
}
