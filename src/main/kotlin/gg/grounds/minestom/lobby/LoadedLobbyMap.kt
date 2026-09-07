package gg.grounds.minestom.lobby

import java.nio.file.Path

internal sealed interface LobbyMapSource {
    data class Published(val address: String, val version: Long, val sha256: String) :
        LobbyMapSource

    data class Local(val root: Path) : LobbyMapSource
}

internal data class LoadedLobbyMap(val root: Path, val source: LobbyMapSource)
