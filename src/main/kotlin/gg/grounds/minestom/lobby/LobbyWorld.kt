package gg.grounds.minestom.lobby

import gg.grounds.vanilla.map.LoadedMap
import gg.grounds.vanilla.map.MapInstanceProvider
import gg.grounds.vanilla.map.MapTemplate
import java.nio.file.Path
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.InstanceContainer

private const val SUNRISE_TIME: Long = 6000

// GROUNDS_LOBBY_MAP_PATH wins when set; otherwise "lobby" is resolved relative to the
// working directory. That's /minestom in the container (Dockerfile WORKDIR, map COPYed
// to /minestom/lobby) and the project root when run locally (the map lives in lobby/
// right next to build.gradle.kts), so the same default works in both places.
private const val DEFAULT_MAP_PATH = "lobby"

/** The loaded lobby instance and the spawn every joining player is teleported to. */
internal data class LobbyMap(val instance: InstanceContainer, val spawn: Pos)

internal object LobbyWorld {
    fun createInstance(): LobbyMap {
        val mapPath = Path.of(System.getenv("GROUNDS_LOBBY_MAP_PATH") ?: DEFAULT_MAP_PATH)

        val map = LoadedMap.load(mapPath)
        val template = MapTemplate.build(map, true).join()
        val provider = MapInstanceProvider(map, template)

        val instanceContainer = provider.provision().instance()
        val spawn = provider.spawn(0) ?: Pos(0.0, 40.0, 0.0)

        val clock = instanceContainer.defaultClock()!!
        clock.rate(0f)
        clock.time(SUNRISE_TIME)

        return LobbyMap(instanceContainer, spawn)
    }
}
