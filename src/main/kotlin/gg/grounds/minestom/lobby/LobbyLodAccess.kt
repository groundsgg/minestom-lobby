package gg.grounds.minestom.lobby

import gg.grounds.permissions.Permissions
import java.util.UUID

/** Uses the current UUID-scoped permission snapshot on each delivery check. */
internal object LobbyLodAccess {
    const val RECEIVE = "grounds.lod.receive"

    fun allowed(permissions: Permissions?, player: UUID): Boolean =
        try {
            permissions?.hasPermission(player, RECEIVE) == true
        } catch (_: Exception) {
            false
        }
}
