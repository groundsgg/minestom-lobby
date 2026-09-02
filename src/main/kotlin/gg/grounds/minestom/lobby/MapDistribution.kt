package gg.grounds.minestom.lobby

import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarConstants
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
    data class Pinned(val version: Long, val bundleSha256: String, val bundleUrl: String)

    /**
     * The unpacked world folder for [address], or null when anything at all goes wrong.
     *
     * Null rather than an exception on purpose: a lobby that cannot reach the CDN should start on
     * the world baked into its image and serve players, not fail to boot. The caller logs which
     * world it ended up with, so "the update did not take" is visible without being fatal.
     */
    fun mapFor(address: String): LoadedLobbyMap? =
        runCatching {
                val pinned = pin(address) ?: return null
                validate(pinned)
                val unpacked = cacheDir.resolve(VERIFIED_CACHE_VERSION).resolve(pinned.bundleSha256)
                if (verified(unpacked, pinned.bundleSha256)) {
                    logger.info(
                        "Using cached {} v{} ({})",
                        address,
                        pinned.version,
                        short(pinned.bundleSha256),
                    )
                    return LoadedLobbyMap(
                        unpacked,
                        LobbyMapSource.Published(address, pinned.version, pinned.bundleSha256),
                    )
                }
                download(pinned, unpacked)
                logger.info("Loaded {} v{} from the map service", address, pinned.version)
                LoadedLobbyMap(
                    unpacked,
                    LobbyMapSource.Published(address, pinned.version, pinned.bundleSha256),
                )
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
            version = entry.get("version").asLong,
            bundleSha256 = entry.get("bundleSha256").asString,
            bundleUrl = entry.get("bundleUrl").asString,
        )
    }

    private fun download(pinned: Pinned, target: Path) {
        val verifiedCache = cacheDir.resolve(VERIFIED_CACHE_VERSION)
        Files.createDirectories(verifiedCache)
        val compressed = Files.createTempFile(verifiedCache, "bundle-", ".tar.zst")
        var staging: Path? = null
        try {
            val response =
                http.send(
                    HttpRequest.newBuilder(URI.create(pinned.bundleUrl))
                        .timeout(Duration.ofMinutes(10))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofInputStream(),
                )
            val digest = MessageDigest.getInstance("SHA-256")
            response.body().use { body ->
                require(response.statusCode() == 200) {
                    "Map bundle answered HTTP ${response.statusCode()}"
                }
                DigestInputStream(BufferedInputStream(body), digest).use { input ->
                    BufferedOutputStream(Files.newOutputStream(compressed)).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            val observed = HexFormat.of().formatHex(digest.digest())
            require(observed == pinned.bundleSha256) { "Map bundle digest mismatch" }

            staging = Files.createTempDirectory(verifiedCache, "unpacking-")
            extract(compressed, staging)
            Files.writeString(staging.resolve(VERIFICATION_MARKER), pinned.bundleSha256)
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
            staging = null
        } catch (failure: Exception) {
            throw failure
        } finally {
            Files.deleteIfExists(compressed)
            staging?.let(::deleteOwnedTree)
        }
    }

    private fun extract(compressed: Path, staging: Path) {
        val entries = mutableSetOf<Path>()
        Files.newInputStream(compressed).use { input ->
            TarArchiveInputStream(ZstdCompressorInputStream(BufferedInputStream(input))).use { tar
                ->
                generateSequence { tar.nextEntry }
                    .forEach { entry ->
                        require(!entry.isSparse) { "unsafe sparse bundle entry: ${entry.name}" }
                        val relative = safeEntryPath(entry.name)
                        require(entries.add(relative)) { "duplicate bundle entry: ${entry.name}" }
                        val file = staging.resolve(relative)
                        require(file.startsWith(staging)) { "bundle entry escapes: ${entry.name}" }
                        when (entry.linkFlag) {
                            TarConstants.LF_DIR -> Files.createDirectories(file)
                            TarConstants.LF_NORMAL,
                            TarConstants.LF_OLDNORM -> {
                                Files.createDirectories(file.parent)
                                Files.newOutputStream(file).use { tar.copyTo(it) }
                            }
                            else ->
                                throw IllegalArgumentException("unsafe bundle entry: ${entry.name}")
                        }
                    }
            }
        }
    }

    private fun safeEntryPath(name: String): Path {
        require(name.isNotBlank() && !name.startsWith('/') && !name.startsWith('\\')) {
            "unsafe bundle entry: $name"
        }
        require('\\' !in name) { "unsafe bundle entry: $name" }
        val segments = name.removeSuffix("/").split('/')
        require(segments.all { it.isNotEmpty() && it != "." && it != ".." }) {
            "unsafe bundle entry: $name"
        }
        val path = Path.of(name).normalize()
        require(!path.isAbsolute && path.toString() != "." && !path.startsWith("..")) {
            "bundle entry escapes: $name"
        }
        return path
    }

    private fun verified(root: Path, digest: String): Boolean =
        Files.isDirectory(root) &&
            Files.isRegularFile(root.resolve(VERIFICATION_MARKER)) &&
            Files.readString(root.resolve(VERIFICATION_MARKER)) == digest

    private fun validate(pinned: Pinned) {
        require(pinned.version > 0) { "Map version must be positive" }
        require(SHA256.matches(pinned.bundleSha256)) {
            "Map bundle digest must be lowercase SHA-256"
        }
        val uri = URI.create(pinned.bundleUrl)
        require(uri.scheme == "http" || uri.scheme == "https") { "Map bundle URL must use HTTP(S)" }
    }

    private fun deleteOwnedTree(root: Path) {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun short(digest: String) = digest.take(12)

    private companion object {
        const val DEFAULT_CDN_BASE = "https://maps.grounds.gg"
        const val DEFAULT_ENVIRONMENT = "stage"
        const val DEFAULT_CACHE_DIR = "/tmp/grounds-maps"
        const val VERIFIED_CACHE_VERSION = "verified-v1"
        const val VERIFICATION_MARKER = ".verified"
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
