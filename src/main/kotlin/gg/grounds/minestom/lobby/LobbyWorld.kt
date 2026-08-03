package gg.grounds.minestom.lobby

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.LightingChunk
import net.minestom.server.instance.anvil.AnvilLoader
import net.minestom.server.utils.chunk.ChunkSupplier

private const val SUNRISE_TIME: Long = 6000

// GROUNDS_LOBBY_MAP_PATH wins when set; otherwise "lobby" is resolved relative to the
// working directory. That's /minestom in the container (Dockerfile WORKDIR, map COPYed
// to /minestom/lobby) and the project root when run locally (the map lives in lobby/
// right next to build.gradle.kts), so the same default works in both places.
private const val DEFAULT_MAP_PATH = "lobby"

/** The map address to pull from the registry, e.g. `lobby/mainlobby`. Unset keeps the baked-in world. */
private const val MAP_ADDRESS_ENV = "GROUNDS_LOBBY_MAP"

/** What a builder marks with `/ms spawn` on the build server. */
private const val SPAWN_POINT = "spawn"

// The map was exported by WorldDownloader, which puts the region files one dimension deep
// rather than in a top-level region/. AnvilLoader wants the directory that *contains*
// region/, so descend when that layout is what we got.
private const val OVERWORLD = "dimensions/minecraft/overworld"

/**
 * Where the world comes from: the pinned version in the map service when `GROUNDS_LOBBY_MAP` names
 * a map, otherwise the folder baked into the image.
 *
 * The fallback is not politeness. A lobby that cannot reach the CDN should still start and serve
 * players on the world it shipped with — an empty lobby is worse than a slightly old one, and the
 * log says which it got.
 */
private fun resolveMapPath(): Path {
    val address = System.getenv(MAP_ADDRESS_ENV)
    if (!address.isNullOrBlank()) {
        MapDistribution().worldFor(address)?.let {
            return it
        }
    }
    return Path.of(System.getenv("GROUNDS_LOBBY_MAP_PATH") ?: DEFAULT_MAP_PATH)
}

/** The loaded lobby instance and the spawn every joining player is teleported to. */
internal data class LobbyMap(val instance: InstanceContainer, val spawn: Pos)

internal object LobbyWorld {
    fun createInstance(): LobbyMap {
        val mapPath = Path.of(System.getenv("GROUNDS_LOBBY_MAP_PATH") ?: DEFAULT_MAP_PATH)
        val world = mapPath.resolve(OVERWORLD).takeIf { Files.isDirectory(it) } ?: mapPath

        val instanceContainer = MinecraftServer.getInstanceManager().createInstanceContainer()
        instanceContainer.chunkLoader = AnvilLoader(world)
        // The authored map ships no light data we can trust after Minestom rewrites blocks,
        // so let it compute lighting per chunk.
        instanceContainer.chunkSupplier = ChunkSupplier { instance, x, z ->
            LightingChunk(instance, x, z)
        }

        val clock = instanceContainer.defaultClock()!!
        clock.rate(0f)
        clock.time(SUNRISE_TIME)

        return LobbyMap(instanceContainer, readSpawn(mapPath))
    }

    /**
     * The spawn lives in the map's own `map.json` sidecar, next to the world data — the same file
     * the gamemodes read. Duplicating it as a constant here would mean the map and the server could
     * disagree about where the map's spawn is.
     */
    private fun readSpawn(mapPath: Path): Pos {
        val sidecar = mapPath.resolve("map.json")
        require(Files.isRegularFile(sidecar)) { "no map.json next to the lobby map at $mapPath" }

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
}
