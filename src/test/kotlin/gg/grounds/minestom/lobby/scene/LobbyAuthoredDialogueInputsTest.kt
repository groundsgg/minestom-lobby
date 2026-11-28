package gg.grounds.minestom.lobby.scene

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LobbyAuthoredDialogueInputsTest {
    @TempDir lateinit var root: Path

    @Test
    fun `empty root is absent and does not need a profile`() {
        assertSame(LobbyAuthoredDialogueInputs.Absent, LobbyAuthoredDialogueInputs.read(root))
    }

    @Test
    fun `unrelated shared symlinks preserve absent Story discovery`() {
        Files.createDirectories(root.resolve("grounds"))
        Files.createDirectories(root.resolve("context"))
        Files.createSymbolicLink(
            root.resolve("grounds/preview.png"),
            root.resolve("missing-preview"),
        )
        Files.createSymbolicLink(root.resolve("context/notes.txt"), root.resolve("missing-notes"))
        assertSame(LobbyAuthoredDialogueInputs.Absent, LobbyAuthoredDialogueInputs.read(root))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "grounds", "context"])
    fun `unrelated root and shared inventories do not consume Story traversal budget`(
        directory: String
    ) {
        unrelatedFiles(root.resolve(directory))
        assertSame(LobbyAuthoredDialogueInputs.Absent, LobbyAuthoredDialogueInputs.read(root))
    }

    @Test
    fun `captured Story ignores unrelated additions replacements and shared subtrees`() {
        complete()
        val captured = LobbyAuthoredDialogueInputs.read(root) as LobbyAuthoredDialogueInputs.Present
        unrelatedFiles(root.resolve("context"))
        write("grounds/preview.png", "before")
        Files.createDirectories(root.resolve("context/other"))
        Files.createSymbolicLink(
            root.resolve("context/other/host-catalog.json"),
            root.resolve("missing"),
        )
        captured.requireUnchanged()
        write("grounds/preview.png", "after")
        captured.requireUnchanged()
        Files.delete(root.resolve("grounds/preview.png"))
        Files.createSymbolicLink(root.resolve("grounds/preview.png"), root.resolve("missing"))
        captured.requireUnchanged()
        assertEquals("{}", captured.dialogues().getValue("a.json").toString(Charsets.UTF_8))
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "dialogues/a.json",
                "texts/en.json",
                "grounds/dialogue.json",
                "context/host-catalog.json",
            ]
    )
    fun `actual Story source symlinks reject before capture`(path: String) {
        complete()
        val target = root.resolve("unrelated.json")
        Files.writeString(target, "{}")
        Files.delete(root.resolve(path))
        Files.createSymbolicLink(root.resolve(path), target)
        assertThrows(IllegalArgumentException::class.java) {
            LobbyAuthoredDialogueInputs.read(root)
        }
    }

    @Test
    fun `complete Story traversal still charges empty library directories`() {
        complete()
        repeat(10000) { Files.createDirectory(root.resolve("dialogues/entry-$it")) }
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                LobbyAuthoredDialogueInputs.read(root)
            }
        assertEquals("Story discovery exceeds limit", failure.message)
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
                "grounds/dialogue.json.",
                "grounds./dialogue.json",
                "Context/host-catalog.json",
                "context/host-catalog.json/child",
            )) {
            val child = Files.createTempDirectory(root, "case")
            Files.createDirectories(child.resolve(path).parent)
            Files.writeString(child.resolve(path), "{}")
            assertThrows(IllegalArgumentException::class.java) {
                LobbyAuthoredDialogueInputs.read(child)
            }
        }
        for (anchor in listOf("dialogues", "texts", "grounds", "context")) {
            val linkRoot = Files.createTempDirectory(root, "links")
            Files.createSymbolicLink(linkRoot.resolve(anchor), root)
            assertThrows(IllegalArgumentException::class.java) {
                LobbyAuthoredDialogueInputs.read(linkRoot)
            }
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

    private fun unrelatedFiles(directory: Path) {
        Files.createDirectories(directory)
        repeat(10001) { Files.createFile(directory.resolve("unrelated-$it")) }
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
