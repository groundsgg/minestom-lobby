package gg.grounds.minestom.lobby.scene

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LobbyDialogueDistributionBindingTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `canonical identity ignores object insertion order and uses integer sizes`() {
        val first =
            JsonParser.parseString(
                """{"z":true,"files":{"scene":{"size":42,"sha256":"abc"}},"a":"x"}"""
            )
        val second =
            JsonParser.parseString(
                """{"a":"x","files":{"scene":{"sha256":"abc","size":42}},"z":true}"""
            )
        assertEquals(
            """{"a":"x","files":{"scene":{"sha256":"abc","size":42}},"z":true}""",
            LobbyDialogueDistributionBinding.canonical(first),
        )
        assertEquals(
            LobbyDialogueDistributionBinding.generation(first),
            LobbyDialogueDistributionBinding.generation(second),
        )
        assertTrue(
            LobbyDialogueDistributionBinding.generation(first).matches(Regex("[0-9a-f]{64}"))
        )
        assertNotEquals(
            LobbyDialogueDistributionBinding.generation(first),
            LobbyDialogueDistributionBinding.generation(
                JsonParser.parseString("""{"a":"changed"}""")
            ),
        )
    }

    @Test
    fun `pins bind bytes independent of directory and reject symlinks`() {
        val a = Files.writeString(directory.resolve("a"), "same bytes")
        val b = Files.writeString(directory.resolve("b"), "same bytes")
        assertEquals(
            LobbyDialogueDistributionBinding.pin(a),
            LobbyDialogueDistributionBinding.pin(b),
        )
        Files.writeString(b, "changed")
        assertNotEquals(
            LobbyDialogueDistributionBinding.pin(a),
            LobbyDialogueDistributionBinding.pin(b),
        )
        val link = Files.createSymbolicLink(directory.resolve("link"), a)
        assertThrows(IllegalArgumentException::class.java) {
            LobbyDialogueDistributionBinding.pin(link)
        }
    }

    @Test
    fun `tooling environment rejects backend credentials and JVM injection`() {
        LobbyDialogueProfileExport.requireToolingEnvironment(
            mapOf("PATH" to "/bin", "HOME" to "/tmp")
        )
        listOf(
                "NATS_URL",
                "GROUNDS_NPC_CONTENT",
                "AWS_SECRET_ACCESS_KEY",
                "JAVA_TOOL_OPTIONS",
                "JDK_JAVA_OPTIONS",
                "_JAVA_OPTIONS",
            )
            .forEach { name ->
                assertThrows(IllegalArgumentException::class.java) {
                    LobbyDialogueProfileExport.requireToolingEnvironment(mapOf(name to "value"))
                }
            }
    }

    @Test
    fun `required private content binding cannot be omitted even with otherwise valid content id`() {
        assertThrows(IllegalArgumentException::class.java) {
            GroundsNpcModelSource.requireContentBinding("abc") { null }
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroundsNpcModelSource.requireContentBinding("abc") { "different".byteInputStream() }
        }
        GroundsNpcModelSource.requireContentBinding("abc") { "abc\n".byteInputStream() }
    }

    @Test
    fun `environment validation is independent from private test content configuration`() {
        val prior = System.getProperty("grounds.npc.content")
        try {
            System.setProperty("grounds.npc.content", "/explicit/private/test/content.zip")
            assertDoesNotThrow {
                LobbyDialogueProfileExport.requireToolingEnvironment(mapOf("HOME" to "/tmp"))
            }
        } finally {
            if (prior == null) System.clearProperty("grounds.npc.content")
            else System.setProperty("grounds.npc.content", prior)
        }
    }

    private fun archive(name: String, entries: Map<String, String>): Path {
        val path = directory.resolve(name)
        java.util.zip.ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            entries.forEach { (key, value) ->
                zip.putNextEntry(java.util.zip.ZipEntry(key))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return path
    }

    @Test
    fun `standalone catalog requires identity metadata even when class bytes match`() {
        val entries = mapOf("gg/grounds/resourcepacks/catalog/GroundsAssetCatalog.class" to "bytes")
        val executable = archive("host.jar", entries)
        val catalog = archive("catalog.jar", entries)
        assertThrows(IllegalArgumentException::class.java) {
            LobbyDialogueDistributionBinding.verifyCatalog(executable, catalog, "1")
        }
    }

    @Test
    fun `catalog pairing checks version bytes and rejects private payloads`() {
        val entries =
            mapOf(
                "gg/grounds/resourcepacks/catalog/GroundsAssetCatalog.class" to "bytes",
                "gg/grounds/resourcepacks/catalog/catalog-version.txt" to "1\n",
            )
        val executable = archive("host.jar", entries)
        val catalog = archive("catalog.jar", entries)
        LobbyDialogueDistributionBinding.verifyCatalog(executable, catalog, "1")
        assertThrows(IllegalArgumentException::class.java) {
            LobbyDialogueDistributionBinding.verifyCatalog(executable, catalog, "2")
        }
        val changed =
            archive(
                "changed.jar",
                entries + ("gg/grounds/resourcepacks/catalog/GroundsAssetCatalog.class" to "other"),
            )
        assertThrows(IllegalArgumentException::class.java) {
            LobbyDialogueDistributionBinding.verifyCatalog(executable, changed, "1")
        }
        val private = archive("private.jar", entries + ("private/model.json" to "secret"))
        assertThrows(IllegalArgumentException::class.java) {
            LobbyDialogueDistributionBinding.verifyCatalog(executable, private, "1")
        }
    }

    @Test
    fun `unsupported CLI arguments fail before producing output`() {
        assertThrows(IllegalArgumentException::class.java) {
            LobbyDialogueProfileExport.export(
                arrayOf("--distribution", directory.toString()),
                emptyMap(),
            )
        }
        assertFalse(Files.exists(directory.resolve("dialogue")))
    }
}
