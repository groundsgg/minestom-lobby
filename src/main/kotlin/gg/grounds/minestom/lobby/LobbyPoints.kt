package gg.grounds.minestom.lobby

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import net.minestom.server.coordinate.Pos
import org.slf4j.LoggerFactory

/**
 * The places a builder marked in the map, read from `grounds/pois.json` inside the world.
 *
 * The file travels in the bundle, so the spawn a version was published with is the spawn that
 * version has — there is no second store to keep in step with whichever version is live.
 */
internal object LobbyPoints {

    private val logger = LoggerFactory.getLogger(LobbyPoints::class.java)

    /** The named point, or null when the map carries none. Never throws: a lobby has a fallback. */
    fun read(worldFolder: Path, name: String): Pos? {
        val file = worldFolder.resolve("grounds").resolve("pois.json")
        if (!Files.isRegularFile(file)) {
            return null
        }
        return runCatching {
                val pois =
                    JsonParser.parseString(Files.readString(file))
                        .asJsonObject
                        .getAsJsonObject("pois")
                val poi = pois?.getAsJsonObject(name) ?: return null
                Pos(
                    poi.get("x").asDouble,
                    poi.get("y").asDouble,
                    poi.get("z").asDouble,
                    poi.get("yaw").asFloat,
                    poi.get("pitch").asFloat,
                )
            }
            .onFailure { logger.warn("Could not read {} from {}", name, file, it) }
            .getOrNull()
    }
}
