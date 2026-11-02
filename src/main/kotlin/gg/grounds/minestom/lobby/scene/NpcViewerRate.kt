package gg.grounds.minestom.lobby.scene

/** Shared identities avoid boxing millisecond values in per-viewer membership maps. */
internal enum class NpcViewerRate(val interval: Long) {
    NEAR(200L),
    MID(250L),
    FAR(500L),
}
