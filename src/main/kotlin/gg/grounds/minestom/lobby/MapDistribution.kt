package gg.grounds.minestom.lobby

import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.slf4j.LoggerFactory

/**
 * Fetches the pinned lobby world from the map service's CDN read path.
 *
 * **The registry itself is never called.** It publishes `pins/<env>.json` to the CDN, and that file
 * names — per map address — the content-addressed bundle to fetch. A lobby therefore boots and
 * loads its world with service-maps down, which is the point of the design rather than a happy
 * accident.
 *
 * Bundles are immutable and named by their own digest, so an unpacked copy is cached under that
 * digest and never fetched twice. A restart that changes nothing costs nothing.
 *
 * @param cdnBase CDN origin. First-party and creator content are served from different hosts, and
 *   the pin file carries absolute bundle URLs for exactly that reason — this is only the fallback
 *   for reading the pin file itself.
 * @param environment which pin file to read, and therefore which version players get. Read from
 *   `MAPS_ENVIRONMENT`, deliberately not `GROUNDS_ENV`: that one belongs to the runtime and accepts
 *   only prod/test/dev, while a pin file is named after a deployment environment such as `stage`.
 */
internal class MapDistribution(
    private val cdnBase: String = System.getenv("MAPS_CDN_BASE") ?: DEFAULT_CDN_BASE,
    private val environment: String = System.getenv("MAPS_ENVIRONMENT") ?: DEFAULT_ENVIRONMENT,
    private val cacheDir: Path = Path.of(System.getenv("MAPS_CACHE_DIR") ?: DEFAULT_CACHE_DIR),
) {

    private val logger = LoggerFactory.getLogger(MapDistribution::class.java)

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    /** What the pin file says about one map. */
    data class Pinned(val version: Int, val bundleSha256: String, val bundleUrl: String)

    /**
     * The unpacked world folder for [address], or null when anything at all goes wrong.
     *
     * Null rather than an exception on purpose: a lobby that cannot reach the CDN should start on
     * the world baked into its image and serve players, not fail to boot. The caller logs which
     * world it ended up with, so "the update did not take" is visible without being fatal.
     */
    fun worldFor(address: String): Path? =
        runCatching {
                val pinned = pin(address) ?: return null
                val unpacked = cacheDir.resolve(pinned.bundleSha256)
                if (Files.isDirectory(unpacked)) {
                    logger.info(
                        "Using cached {} v{} ({})",
                        address,
                        pinned.version,
                        short(pinned.bundleSha256),
                    )
                    return unpacked
                }
                download(pinned, unpacked)
                logger.info("Loaded {} v{} from the map service", address, pinned.version)
                unpacked
            }
            .onFailure { logger.warn("Could not get {} from the map service", address, it) }
            .getOrNull()

    private fun pin(address: String): Pinned? {
        val url = "${cdnBase.trimEnd('/')}/pins/$environment.json"
        val response =
            http.send(
                HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        if (response.statusCode() != 200) {
            logger.warn("Pin file {} answered HTTP {}", url, response.statusCode())
            return null
        }
        val maps = JsonParser.parseString(response.body()).asJsonObject.getAsJsonObject("maps")
        val entry = maps?.getAsJsonObject(address)
        if (entry == null) {
            logger.warn("No pin for {} in {}", address, environment)
            return null
        }
        return Pinned(
            version = entry.get("version").asInt,
            bundleSha256 = entry.get("bundleSha256").asString,
            bundleUrl = entry.get("bundleUrl").asString,
        )
    }

    private fun download(pinned: Pinned, target: Path) {
        // Unpacked beside the target and moved into place: a half-extracted world that another
        // boot mistakes for a cache hit is a lobby with holes in it.
        val staging =
            Files.createTempDirectory(cacheDir.also { Files.createDirectories(it) }, "unpacking-")
        try {
            http
                .send(
                    HttpRequest.newBuilder(URI.create(pinned.bundleUrl))
                        .timeout(Duration.ofMinutes(10))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofInputStream(),
                )
                .body()
                .use { body ->
                    TarArchiveInputStream(ZstdCompressorInputStream(BufferedInputStream(body)))
                        .use { tar ->
                            generateSequence { tar.nextEntry }
                                .filter { !it.isDirectory }
                                .forEach { entry ->
                                    val file = staging.resolve(entry.name).normalize()
                                    // A bundle is fetched over the network; an entry named `../..`
                                    // would write outside the cache directory entirely.
                                    require(file.startsWith(staging)) {
                                        "bundle entry escapes: ${entry.name}"
                                    }
                                    Files.createDirectories(file.parent)
                                    Files.newOutputStream(file).use { tar.copyTo(it) }
                                }
                        }
                }
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: Exception) {
            staging.toFile().deleteRecursively()
            throw failure
        }
    }

    private fun short(digest: String) = digest.take(12)

    private companion object {
        const val DEFAULT_CDN_BASE = "https://maps.grounds.gg"
        const val DEFAULT_ENVIRONMENT = "stage"
        const val DEFAULT_CACHE_DIR = "/tmp/grounds-maps"
    }
}
