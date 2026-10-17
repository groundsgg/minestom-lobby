package gg.grounds.minestom.lobby

import gg.grounds.runtime.core.world.GroundsWorld
import gg.grounds.runtime.core.world.WorldIdentity
import java.nio.file.Files
import java.nio.file.Path
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.anvil.AnvilLoader
import net.minestom.server.world.DimensionType

private const val SUNRISE_TIME: Long = 6000

// GROUNDS_LOBBY_MAP_PATH wins when set; otherwise "lobby" is resolved relative to the
// working directory. That's /minestom in the container (Dockerfile WORKDIR, map COPYed
// to /minestom/lobby) and the project root when run locally (the map lives in lobby/
// right next to build.gradle.kts), so the same default works in both places.
private const val DEFAULT_MAP_PATH = "lobby"

/**
 * The map address to pull from the registry, e.g. `lobby/mainlobby`. Unset keeps the baked-in
 * world.
 */
private const val MAP_ADDRESS_ENV = "GROUNDS_LOBBY_MAP"

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
private fun resolveMap(): LoadedLobbyMap {
    val address = System.getenv(MAP_ADDRESS_ENV)
    if (!address.isNullOrBlank()) {
        MapDistribution().mapFor(address)?.let {
            return it
        }
    }
    val root = Path.of(System.getenv("GROUNDS_LOBBY_MAP_PATH") ?: DEFAULT_MAP_PATH)
    return LoadedLobbyMap(root, LobbyMapSource.Local(root))
}

/**
 * The directory to hand [AnvilLoader]: the one that *contains* `region/`.
 *
 * Two layouts arrive here. A world exported by WorldDownloader keeps its region files one dimension
 * deep, so descend. A bundle from the map registry has `region/` at the top, so do not.
 *
 * Deliberately paired with the one-argument [AnvilLoader]. The dimension-aware constructor resolves
 * `<path>/dimensions/<namespace>/<value>/region` itself, and this function has already stripped
 * exactly that segment — using both appends the dimension path twice, which is a directory no
 * layout has. The chunk loader then finds nothing, every chunk comes back empty, and a player
 * standing in a finished lobby sees no world at all.
 */
internal fun regionRoot(mapPath: Path): Path =
    mapPath.resolve(OVERWORLD).takeIf { Files.isDirectory(it) } ?: mapPath

/** The loaded lobby instance and the spawn every joining player is teleported to. */
internal data class LobbyMap(
    val instance: InstanceContainer,
    val spawn: Pos,
    val loaded: LoadedLobbyMap,
)

/**
 * What clients tell this world apart by, so LOD mods keep its cache separate from every game map. A
 * published map is keyed by its address and version, so a new version gets a fresh cache; the
 * baked-in world has neither and shares one fixed identity.
 */
internal fun worldIdentity(source: LobbyMapSource): WorldIdentity =
    when (source) {
        is LobbyMapSource.Published -> WorldIdentity.of(source.address, source.version.toInt())
        is LobbyMapSource.Local -> WorldIdentity.of(LOCAL_MAP_ADDRESS)
    }

private const val LOCAL_MAP_ADDRESS = "lobby/local"

internal object LobbyWorld {
    fun createInstance(): LobbyMap = open(resolveMap())

    fun open(loaded: LoadedLobbyMap): LobbyMap {
        val mapPath = loaded.root

        // Opened under its own identity, with every loaded chunk relit: the authored map ships no
        // light data we can trust after Minestom rewrites blocks.
        // The vanilla clock model selects its daytime animation by dimension name, not type.
        // Custom names use the random Nether animation even with a frozen Overworld clock.
        // Keep the map-specific seed so LOD caches still distinguish maps and their versions.
        val identity = worldIdentity(loaded.source).copy(key = DimensionType.OVERWORLD.key())
        val instanceContainer = GroundsWorld.open(regionRoot(mapPath), identity)

        val clock = instanceContainer.defaultClock()!!
        clock.rate(0f)
        clock.time(SUNRISE_TIME)

        return LobbyMap(instanceContainer, LobbySpawn.resolve(mapPath), loaded)
    }
}
