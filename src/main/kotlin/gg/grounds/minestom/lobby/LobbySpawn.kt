package gg.grounds.minestom.lobby

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import net.minestom.server.coordinate.Pos

/**
 * Resolves where players land from the map itself — never a constant, or the map and server could
 * disagree about the map's own spawn.
 *
 * A versioned point in `grounds/pois.json` wins over the `map.json` sidecar an exported world
 * carries. A map from the registry has no sidecar, so requiring one would prevent a lobby from
 * starting when it loads a published version.
 */
internal object LobbySpawn {
    fun resolve(mapPath: Path): Pos = LobbyPoints.read(mapPath, SPAWN_POINT) ?: readSidecar(mapPath)

    private fun readSidecar(mapPath: Path): Pos {
        val sidecar = mapPath.resolve("map.json")
        require(Files.isRegularFile(sidecar)) {
            "the lobby map at $mapPath carries neither grounds/pois.json nor map.json, so nothing" +
                " says where players should land"
        }

        val spawns = JsonParser.parseString(Files.readString(sidecar)).asJsonObject["spawns"]
        val spawn = spawns.asJsonArray.first().asJsonObject
        return Pos(
            spawn["x"].asDouble,
            spawn["y"].asDouble,
            spawn["z"].asDouble,
            spawn["yaw"]?.asFloat ?: 0f,
            spawn["pitch"]?.asFloat ?: 0f,
        )
    }

    private const val SPAWN_POINT = "spawn"
}
