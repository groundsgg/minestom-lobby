package gg.grounds.minestom.lobby

import gg.grounds.lod.runtime.LodExpectation
import gg.grounds.lod.runtime.LodPilotHttpClient
import gg.grounds.lod.runtime.MinestomLodAdapter
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import org.slf4j.LoggerFactory

/** Explicit finite pilot. Default-off selection never accesses credentials or the control API. */
internal class LobbyLodPilotSettings
private constructor(
    val origin: URI,
    val run: UUID,
    val players: Set<UUID>,
    val generatorSha256: String,
    private val capabilityFile: Path,
    val cacheDirectory: Path,
) {
    fun expectation(loaded: LoadedLobbyMap, registrySha256: String): LodExpectation? {
        val source = loaded.source as? LobbyMapSource.Published ?: return null
        if (
            source.address != "lobby/mainlobby" ||
                source.version != 13L ||
                !loaded.prelit ||
                loaded.environment != "stage"
        )
            return null
        val input = loaded.transportSha256 ?: return null
        if (!sha.matches(source.sha256) || !sha.matches(input)) return null
        return LodExpectation(source.sha256, input, registrySha256, generatorSha256)
    }

    fun readCapability(): String {
        val bytes = Files.newInputStream(capabilityFile).use { it.readNBytes(259) }
        require(bytes.size <= 258) { "Invalid LOD pilot capability" }
        val token = bytes.toString(Charsets.UTF_8).trimEnd('\r', '\n')
        require(token.length in 32..256 && token.all { it.code in 33..126 }) {
            "Invalid LOD pilot capability"
        }
        return token
    }

    companion object {
        private val sha = Regex("[a-f0-9]{64}")

        fun fromEnvironment(env: Map<String, String>): LobbyLodPilotSettings? {
            if (env["GROUNDS_LOD_PILOT_ENABLED"] != "true") return null
            require((env["MAPS_ENVIRONMENT"] == null || env["MAPS_ENVIRONMENT"] == "stage")) {
                "LOD pilot requires stage"
            }
            fun required(key: String) =
                requireNotNull(env[key]?.takeIf { it.isNotBlank() }) {
                    "Missing LOD pilot configuration"
                }
            fun uuid(value: String) =
                UUID.fromString(value).also { require(it.toString() == value) }
            val run = uuid(required("GROUNDS_LOD_PILOT_RUN_ID"))
            val raw = required("GROUNDS_LOD_PILOT_PLAYERS").split(',').map { it.trim() }
            require(raw.size in 1..5 && raw.distinct().size == raw.size) {
                "LOD pilot requires 1..5 UUIDs"
            }
            val players = raw.map(::uuid).toSet()
            val generator = required("GROUNDS_LOD_PILOT_GENERATOR_SHA256")
            require(sha.matches(generator)) { "Invalid LOD generator identity" }
            val origin = URI(env["GROUNDS_LOD_PILOT_SERVICE_ORIGIN"] ?: "https://api.grounds.gg")
            require(
                origin.scheme == "https" &&
                    origin.host != null &&
                    origin.userInfo == null &&
                    origin.query == null &&
                    origin.fragment == null &&
                    (origin.path.isNullOrEmpty() || origin.path == "/")
            ) {
                "Invalid LOD control origin"
            }
            val cache =
                env["GROUNDS_LOD_PILOT_CACHE_DIR"]?.let(Path::of)
                    ?: Path.of(System.getProperty("java.io.tmpdir"), "grounds-lod-pilot")
            require(cache.isAbsolute) { "LOD pilot cache must use an absolute path" }
            return LobbyLodPilotSettings(
                origin,
                run,
                players,
                generator,
                Path.of(required("GROUNDS_LOD_PILOT_CAPABILITY_FILE")),
                cache,
            )
        }
    }
}

internal object LobbyLodPilot {
    private val logger = LoggerFactory.getLogger(LobbyLodPilot::class.java)

    fun install(
        parent: EventNode<Event>,
        lobby: LobbyMap,
        env: Map<String, String>,
    ): AutoCloseable? =
        configure(env, lobby.loaded, MinestomLodAdapter::registrySha256) { settings, expected ->
            val client =
                LodPilotHttpClient(
                    settings.origin,
                    settings.run,
                    settings.readCapability(),
                    expected.mapSha256,
                )
            MinestomLodAdapter.install(
                parent,
                lobby.instance,
                expected,
                client,
                settings.players,
                settings.cacheDirectory,
            )
        }

    fun configure(
        env: Map<String, String>,
        loaded: LoadedLobbyMap,
        registry: () -> String,
        install: (LobbyLodPilotSettings, LodExpectation) -> AutoCloseable,
    ): AutoCloseable? {
        val settings =
            try {
                LobbyLodPilotSettings.fromEnvironment(env)
            } catch (_: Exception) {
                logger.warn("LOD pilot disabled: invalid or incomplete configuration")
                return null
            } ?: return null
        return try {
            val expected = settings.expectation(loaded, registry())
            if (expected == null) {
                logger.warn("LOD pilot disabled: loaded map is not the prelit stage Lobby v13")
                null
            } else
                install(settings, expected).also {
                    logger.info(
                        "Installed private LOD pilot (run={}, players={})",
                        settings.run,
                        settings.players.size,
                    )
                }
        } catch (_: Exception) {
            // A credential-reading/optional runtime error must not fail the base Lobby.
            // Never log exception messages which might contain capability material or URLs.
            logger.warn("LOD pilot disabled: optional runtime installation failed")
            null
        }
    }
}
