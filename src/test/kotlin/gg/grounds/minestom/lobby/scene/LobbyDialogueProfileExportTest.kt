package gg.grounds.minestom.lobby.scene

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LobbyDialogueProfileExportTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `invalid explicit contract fails before touching distribution inputs or outputs`() {
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                LobbyDialogueProfileExport.export(
                    arrayOf(
                        "--distribution",
                        directory.toString(),
                        "--scene",
                        directory.toString(),
                        "--host-contract",
                        "author:arrival",
                    ),
                    emptyMap(),
                )
            }
        assertTrue(failure.message!!.contains("Unsupported lobby host contract"))
        assertFalse(Files.exists(directory.resolve("dialogue")))
        Files.list(directory).use { assertEquals(0L, it.count()) }
    }

    @Test
    fun `duplicate missing and unknown exporter flags fail without output`() {
        listOf(
                arrayOf("--distribution", directory.toString()),
                arrayOf(
                    "--distribution",
                    directory.toString(),
                    "--distribution",
                    directory.toString(),
                ),
                arrayOf(
                    "--distribution",
                    directory.toString(),
                    "--scene",
                    directory.toString(),
                    "--other",
                    "grounds:lobby-guide",
                ),
            )
            .forEach { arguments ->
                assertThrows(IllegalArgumentException::class.java) {
                    LobbyDialogueProfileExport.export(arguments, emptyMap())
                }
                assertFalse(Files.exists(directory.resolve("dialogue")))
            }
    }
}
