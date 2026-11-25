package gg.grounds.minestom.lobby.scene

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LobbyAuthoredDialogueInputsTest {
    @TempDir lateinit var root: Path

    @Test
    fun `empty root is absent and does not need a profile`() {
        assertSame(LobbyAuthoredDialogueInputs.Absent, LobbyAuthoredDialogueInputs.read(root))
    }

    @Test
    fun `texts alone are partial authored input`() {
        write("texts/en.json", "{}")
        assertThrows(IllegalArgumentException::class.java) {
            LobbyAuthoredDialogueInputs.read(root)
        }
    }

    @Test
    fun `reserved aliases and symlinks cannot become absence`() {
        for (path in
            listOf(
                "Dialogues/a.json",
                "texts./en.json",
                "grounds/Dialogue.json",
                "context/host-catalog.json/child",
            )) {
            val child = Files.createTempDirectory(root, "case")
            Files.createDirectories(child.resolve(path).parent)
            Files.writeString(child.resolve(path), "{}")
            assertThrows(IllegalArgumentException::class.java) {
                LobbyAuthoredDialogueInputs.read(child)
            }
        }
        val linkRoot = Files.createTempDirectory(root, "links")
        Files.createSymbolicLink(linkRoot.resolve("dialogues"), root)
        assertThrows(IllegalArgumentException::class.java) {
            LobbyAuthoredDialogueInputs.read(linkRoot)
        }
    }

    @Test
    fun `complete inventory is captured with defensive copies and repeated pins`() {
        complete()
        val captured = LobbyAuthoredDialogueInputs.read(root) as LobbyAuthoredDialogueInputs.Present
        captured.dialogues().getValue("a.json")[0] = 0
        assertEquals("{}", captured.dialogues().getValue("a.json").toString(Charsets.UTF_8))
        captured.requireUnchanged()
        write("texts/en.json", """{"changed":"yes"}""")
        assertThrows(IllegalArgumentException::class.java) { captured.requireUnchanged() }
    }

    @Test
    fun `invalid UTF8 duplicate JSON noncanonical locale and nested text fail`() {
        for (path in listOf("texts/en-us.json", "texts/sub/en.json", "dialogues/a.txt")) {
            complete()
            write(path, "{}")
            assertThrows(IllegalArgumentException::class.java) {
                LobbyAuthoredDialogueInputs.read(root)
            }
            Files.delete(root.resolve(path))
        }
        complete()
        write("dialogues/a.json", """{"id":1,"id":2}""")
        assertThrows(IllegalArgumentException::class.java) {
            LobbyAuthoredDialogueInputs.read(root)
        }
        Files.write(root.resolve("dialogues/a.json"), byteArrayOf(0xc3.toByte()))
        assertThrows(IllegalArgumentException::class.java) {
            LobbyAuthoredDialogueInputs.read(root)
        }
    }

    @Test
    fun `oversize files and portable path aliases reject before decoding`() {
        complete()
        Files.write(root.resolve("dialogues/a.json"), ByteArray(1048577) { 32 })
        assertThrows(IllegalArgumentException::class.java) {
            LobbyAuthoredDialogueInputs.read(root)
        }
        complete()
        write("dialogues/A.json", "{}")
        assertThrows(IllegalArgumentException::class.java) {
            LobbyAuthoredDialogueInputs.read(root)
        }
        Files.delete(root.resolve("dialogues/A.json"))
        write("dialogues/CON.json", "{}")
        assertThrows(IllegalArgumentException::class.java) {
            LobbyAuthoredDialogueInputs.read(root)
        }
    }

    @Test
    fun `adding a source after capture changes the inventory`() {
        complete()
        val captured = LobbyAuthoredDialogueInputs.read(root) as LobbyAuthoredDialogueInputs.Present
        write("dialogues/new.json", "{}")
        assertThrows(IllegalArgumentException::class.java) { captured.requireUnchanged() }
    }

    private fun complete() {
        write("dialogues/a.json", "{}")
        write("texts/en.json", "{}")
        write("context/host-catalog.json", "{}")
        write("grounds/dialogue.json", "{}")
    }

    private fun write(path: String, value: String) {
        Files.createDirectories(root.resolve(path).parent)
        Files.writeString(root.resolve(path), value)
    }
}
