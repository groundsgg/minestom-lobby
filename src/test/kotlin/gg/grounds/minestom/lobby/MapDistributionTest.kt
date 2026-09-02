package gg.grounds.minestom.lobby

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MapDistributionTest {

    @Test
    fun `same cached digest preserves a newly selected address and version`(@TempDir cache: Path) {
        val archive = archive("map.json" to "{}")
        val digest = sha256(archive)
        val selected = java.util.concurrent.atomic.AtomicReference("lobby/main" to 42)
        server(
                pin = { base ->
                    selected.get().let { (address, version) ->
                        pin(address, version, digest, "$base/bundle")
                    }
                },
                bundle = archive,
            )
            .use { fixture ->
                val distribution = MapDistribution(fixture.baseUrl, "test", cache)
                val first = distribution.mapFor("lobby/main")!!
                selected.set("lobby/other" to 99)
                val second = distribution.mapFor("lobby/other")!!
                assertEquals(first.root, second.root)
                assertEquals(LobbyMapSource.Published("lobby/other", 99, digest), second.source)
                assertEquals(1, fixture.bundleRequests.get())
            }
    }

    @Test
    fun `returns the pinned identity and verified root without redownloading`(
        @TempDir cache: Path
    ) {
        val archive = archive("map.json" to "{}", "scene.json" to "{\\\"format\\\":1}")
        val digest = sha256(archive)
        server(pin = { base -> pin("lobby/main", 42, digest, "$base/bundle") }, bundle = archive)
            .use { fixture ->
                val distribution = MapDistribution(fixture.baseUrl, "test", cache)

                val first = distribution.mapFor("lobby/main")
                val second = distribution.mapFor("lobby/main")

                val root = cache.resolve("verified-v1").resolve(digest)
                assertEquals(
                    LoadedLobbyMap(root, LobbyMapSource.Published("lobby/main", 42, digest)),
                    first,
                )
                assertEquals(first, second)
                assertTrue(Files.isRegularFile(root.resolve("map.json")))
                assertTrue(Files.isRegularFile(root.resolve("scene.json")))
                assertEquals(1, fixture.bundleRequests.get())
            }
    }

    @Test
    fun `does not promote a bundle whose compressed bytes differ from its pin`(
        @TempDir cache: Path
    ) {
        val archive = archive("map.json" to "{}", "scene.json" to "{}")
        server(
                pin = { base -> pin("lobby/main", 1, "0".repeat(64), "$base/bundle") },
                bundle = archive,
            )
            .use { fixture ->
                assertNull(MapDistribution(fixture.baseUrl, "test", cache).mapFor("lobby/main"))
            }

        assertFalse(Files.exists(cache.resolve("verified-v1").resolve("0".repeat(64))))
    }

    @Test
    fun `does not promote a non-successful bundle response`(@TempDir cache: Path) {
        val archive = archive("map.json" to "{}", "scene.json" to "{}")
        val digest = sha256(archive)
        server(
                pin = { base -> pin("lobby/main", 1, digest, "$base/bundle") },
                bundle = archive,
                bundleStatus = 503,
            )
            .use { fixture ->
                assertNull(MapDistribution(fixture.baseUrl, "test", cache).mapFor("lobby/main"))
            }

        assertFalse(Files.exists(cache.resolve("verified-v1").resolve(digest)))
    }

    @Test
    fun `rejects unsafe paths and duplicate archive entries without promoting them`(
        @TempDir cache: Path
    ) {
        for (entries in
            listOf(
                arrayOf("../escape" to "no"),
                arrayOf("same" to "one", "same" to "two"),
                arrayOf("link" to "target"),
            )) {
            val archive = archive(*entries, link = entries.singleOrNull()?.first == "link")
            val digest = sha256(archive)
            server(pin = { base -> pin("lobby/main", 1, digest, "$base/bundle") }, bundle = archive)
                .use { fixture ->
                    assertNull(MapDistribution(fixture.baseUrl, "test", cache).mapFor("lobby/main"))
                }
            assertFalse(Files.exists(cache.resolve("verified-v1").resolve(digest)))
        }
    }

    @Test
    fun `rejects every unsupported tar type with ordinary and trailing slash names`(
        @TempDir cache: Path
    ) {
        val unsafeTypes =
            listOf(
                TarConstants.LF_FIFO,
                TarConstants.LF_CHR,
                TarConstants.LF_BLK,
                '?'.code.toByte(),
                TarConstants.LF_LINK,
                TarConstants.LF_SYMLINK,
            )
        for (type in unsafeTypes) {
            for (name in listOf("unsafe-${type.toInt()}", "unsafe-${type.toInt()}/")) {
                val archive = archiveEntry(name, type)
                val digest = sha256(archive)
                server(
                        pin = { base -> pin("lobby/main", 1, digest, "$base/bundle") },
                        bundle = archive,
                    )
                    .use { fixture ->
                        assertNull(
                            MapDistribution(fixture.baseUrl, "test", cache).mapFor("lobby/main"),
                            "$type $name",
                        )
                    }
                assertFalse(
                    Files.exists(cache.resolve("verified-v1").resolve(digest)),
                    "$type $name",
                )
            }
        }
    }

    @Test
    fun `rejects a PAX sparse entry without promoting it`(@TempDir cache: Path) {
        val archive = paxSparseArchive()
        assertTrue(isSparse(archive))
        val digest = sha256(archive)
        server(pin = { base -> pin("lobby/main", 1, digest, "$base/bundle") }, bundle = archive)
            .use { fixture ->
                assertNull(MapDistribution(fixture.baseUrl, "test", cache).mapFor("lobby/main"))
            }

        assertFalse(Files.exists(cache.resolve("verified-v1").resolve(digest)))
    }

    @Test
    fun `does not trust an old unverified cache directory`(@TempDir cache: Path) {
        val archive = archive("map.json" to "{}", "scene.json" to "{}")
        val digest = sha256(archive)
        cache.resolve(digest).createDirectories()
        cache.resolve(digest).resolve("old.txt").toFile().writeText("unverified")
        server(pin = { base -> pin("lobby/main", 7, digest, "$base/bundle") }, bundle = archive)
            .use { fixture ->
                val result = MapDistribution(fixture.baseUrl, "test", cache).mapFor("lobby/main")

                assertEquals(cache.resolve("verified-v1").resolve(digest), result?.root)
                assertEquals(1, fixture.bundleRequests.get())
            }
        assertTrue(Files.isRegularFile(cache.resolve(digest).resolve("old.txt")))
    }

    private fun pin(address: String, version: Int, digest: String, bundleUrl: String): String =
        """{"maps":{"$address":{"version":$version,"bundleSha256":"$digest","bundleUrl":"$bundleUrl"}}}"""

    private fun archive(vararg files: Pair<String, String>, link: Boolean = false): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            ZstdCompressorOutputStream(bytes).use { zstd ->
                TarArchiveOutputStream(zstd).use { tar ->
                    files.forEach { (name, content) ->
                        val contentBytes = content.toByteArray(StandardCharsets.UTF_8)
                        val entry =
                            if (link) TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK)
                            else TarArchiveEntry(name)
                        if (link) {
                            entry.setLinkName(content)
                            entry.size = 0
                        } else {
                            entry.size = contentBytes.size.toLong()
                        }
                        tar.putArchiveEntry(entry)
                        if (!link) tar.write(contentBytes)
                        tar.closeArchiveEntry()
                    }
                }
            }
            bytes.toByteArray()
        }

    private fun archiveEntry(name: String, type: Byte): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            ZstdCompressorOutputStream(bytes).use { zstd ->
                TarArchiveOutputStream(zstd).use { tar ->
                    val entry = TarArchiveEntry(name, type)
                    if (type == TarConstants.LF_LINK || type == TarConstants.LF_SYMLINK) {
                        entry.setLinkName("target")
                    }
                    entry.size = 0
                    tar.putArchiveEntry(entry)
                    tar.closeArchiveEntry()
                }
            }
            bytes.toByteArray()
        }

    private fun paxSparseArchive(): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            ZstdCompressorOutputStream(bytes).use { zstd ->
                TarArchiveOutputStream(zstd).use { tar ->
                    val headers =
                        paxRecords("GNU.sparse.size" to "0", "GNU.sparse.numblocks" to "0")
                    val pax =
                        TarArchiveEntry("PaxHeaders/sparse", TarConstants.LF_PAX_EXTENDED_HEADER_LC)
                    pax.size = headers.size.toLong()
                    tar.putArchiveEntry(pax)
                    tar.write(headers)
                    tar.closeArchiveEntry()

                    val entry = TarArchiveEntry("sparse", TarConstants.LF_NORMAL)
                    entry.size = 0
                    tar.putArchiveEntry(entry)
                    tar.closeArchiveEntry()
                }
            }
            bytes.toByteArray()
        }

    private fun paxRecords(vararg headers: Pair<String, String>): ByteArray =
        headers
            .joinToString(separator = "") { (key, value) ->
                generateSequence(0) { length -> "$length $key=$value\n".length }
                    .drop(1)
                    .first { length -> "$length $key=$value\n".length == length }
                    .let { length -> "$length $key=$value\n" }
            }
            .toByteArray(StandardCharsets.UTF_8)

    private fun isSparse(archive: ByteArray): Boolean =
        TarArchiveInputStream(ZstdCompressorInputStream(BufferedInputStream(archive.inputStream())))
            .use { tar -> tar.nextEntry.isSparse }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun server(
        pin: (String) -> String,
        bundle: ByteArray,
        bundleStatus: Int = 200,
    ): ServerFixture {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = AtomicInteger()
        server.createContext("/pins/test.json") { exchange ->
            exchange.respond(200, pin(baseUrl(server)))
        }
        server.createContext("/bundle") { exchange ->
            requests.incrementAndGet()
            exchange.respond(bundleStatus, bundle)
        }
        server.start()
        return ServerFixture(server, baseUrl(server), requests)
    }

    private fun baseUrl(server: HttpServer): String = "http://127.0.0.1:${server.address.port}"

    private fun HttpExchange.respond(status: Int, body: String) =
        respond(status, body.toByteArray())

    private fun HttpExchange.respond(status: Int, body: ByteArray) {
        sendResponseHeaders(status, body.size.toLong())
        responseBody.use { it.write(body) }
    }

    private class ServerFixture(
        private val server: HttpServer,
        val baseUrl: String,
        val bundleRequests: AtomicInteger,
    ) : AutoCloseable {
        override fun close() = server.stop(0)
    }
}
