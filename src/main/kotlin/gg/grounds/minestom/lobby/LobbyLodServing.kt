package gg.grounds.minestom.lobby

import gg.grounds.lod.runtime.LodEntitledHttpClient
import gg.grounds.lod.runtime.LodExpectation
import gg.grounds.lod.runtime.MinestomLodAdapter
import gg.grounds.permissions.Permissions
import io.micrometer.core.instrument.MeterRegistry
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import org.slf4j.LoggerFactory

/** Explicit ordinary Stage serving; the control API owns durable shared quotas. */
internal class LobbyLodServingSettings
private constructor(
    val origin: URI,
    val generatorSha256: String,
    val cacheDirectory: Path,
    private val capabilityFile: Path?,
    private val capabilityValue: String?,
) {
    fun expectation(loaded: LoadedLobbyMap, registrySha256: String): LodExpectation? {
        val source = loaded.source as? LobbyMapSource.Published ?: return null
        val transport = loaded.transportSha256 ?: return null
        if (
            source.address != "lobby/mainlobby" ||
                source.version <= 0 ||
                !loaded.prelit ||
                loaded.environment != "stage" ||
                !sha.matches(source.sha256) ||
                !sha.matches(transport)
        )
            return null
        return LodExpectation(source.sha256, transport, registrySha256, generatorSha256)
    }

    fun readCapability(): String {
        val value =
            if (capabilityFile != null) {
                val bytes = Files.newInputStream(capabilityFile).use { it.readNBytes(259) }
                require(bytes.size <= 258) { "Invalid LOD serving capability" }
                bytes.toString(Charsets.UTF_8).trimEnd('\r', '\n')
            } else requireNotNull(capabilityValue)
        return value.also { token ->
            require(token.length in 32..256 && token.all { it.code in 33..126 }) {
                "Invalid LOD serving capability"
            }
        }
    }

    companion object {
        private val sha = Regex("[a-f0-9]{64}")

        fun fromEnvironment(env: Map<String, String>): LobbyLodServingSettings? {
            if (env["GROUNDS_LOD_SERVING_ENABLED"] != "true") return null
            require(env["GROUNDS_LOD_PILOT_ENABLED"] != "true") { "Conflicting LOD modes" }
            require(env["MAPS_ENVIRONMENT"] == null || env["MAPS_ENVIRONMENT"] == "stage") {
                "LOD serving requires Stage"
            }
            fun required(key: String) =
                requireNotNull(env[key]?.takeIf(String::isNotBlank)) {
                    "Missing LOD serving configuration"
                }
            val generator = required("GROUNDS_LOD_SERVING_GENERATOR_SHA256")
            require(sha.matches(generator)) { "Invalid LOD generator identity" }
            val origin = URI(env["GROUNDS_LOD_SERVING_SERVICE_ORIGIN"] ?: "https://api.grounds.gg")
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
                env["GROUNDS_LOD_SERVING_CACHE_DIR"]?.let(Path::of)
                    ?: Path.of(System.getProperty("java.io.tmpdir"), "grounds-lod-serving")
            require(cache.isAbsolute) { "LOD cache requires an absolute path" }
            val capability = env["GROUNDS_LOD_SERVING_CAPABILITY_FILE"]?.let(Path::of)
            val value = env["GROUNDS_LOD_SERVING_CAPABILITY"]
            require((capability == null) != (value == null)) { "Select one LOD serving credential" }
            require(capability == null || capability.isAbsolute) {
                "LOD capability requires an absolute path"
            }
            return LobbyLodServingSettings(origin, generator, cache, capability, value)
        }
    }
}

internal object LobbyLodServing {
    private val logger = LoggerFactory.getLogger(LobbyLodServing::class.java)

    fun install(
        parent: EventNode<Event>,
        lobby: LobbyMap,
        env: Map<String, String>,
        permissions: () -> Permissions?,
        metrics: () -> MeterRegistry?,
    ): AutoCloseable? =
        configure(env, lobby.loaded, MinestomLodAdapter::registrySha256) { settings, expected ->
            // Discovery is deferred until an entitled compatible client needs the immutable source.
            val control =
                LodEntitledHttpClient(settings.origin, settings.readCapability(), expected)
            val adapter =
                MinestomLodAdapter.installEntitled(
                    parent,
                    lobby.instance,
                    expected,
                    control,
                    settings.cacheDirectory,
                    { player -> LobbyLodAccess.allowed(permissions(), player) },
                )
            val meters =
                try {
                    metrics()?.let { LobbyLodMetrics(it, adapter::stats, adapter::deliveryStats) }
                } catch (_: Exception) {
                    logger.warn("LOD delivery metrics unavailable")
                    null
                }
            AutoCloseable {
                try {
                    adapter.close()
                } finally {
                    meters?.close()
                }
            }
        }

    fun configure(
        env: Map<String, String>,
        loaded: LoadedLobbyMap,
        registry: () -> String,
        install: (LobbyLodServingSettings, LodExpectation) -> AutoCloseable,
    ): AutoCloseable? {
        val settings =
            try {
                LobbyLodServingSettings.fromEnvironment(env)
            } catch (_: Exception) {
                logger.warn("LOD serving disabled: invalid or incomplete configuration")
                return null
            } ?: return null
        return try {
            val expected = settings.expectation(loaded, registry())
            if (expected == null) {
                logger.warn(
                    "LOD serving disabled: loaded map is not the published prelit Stage Lobby"
                )
                null
            } else
                install(settings, expected).also {
                    logger.info("Installed permission-gated LOD serving (map=lobby/mainlobby)")
                }
        } catch (_: Exception) {
            logger.warn("LOD serving disabled: optional runtime installation failed")
            null
        }
    }
}
