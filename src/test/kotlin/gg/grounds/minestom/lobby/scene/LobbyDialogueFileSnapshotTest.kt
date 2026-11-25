package gg.grounds.minestom.lobby.scene

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LobbyDialogueFileSnapshotTest {
    @Test
    fun `growing stream is rejected after at most the limit plus one byte`() {
        var consumed = 0
        val growing =
            object : InputStream() {
                override fun read(): Int {
                    consumed++
                    return 32
                }

                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    consumed += length
                    bytes.fill(32, offset, offset + length)
                    return length
                }
            }
        assertThrows(IllegalArgumentException::class.java) {
            LobbyDialogueFileSnapshot.read(growing, 1024)
        }
        assertEquals(1025, consumed)
    }

    @Test
    fun `pin describes the bounded bytes passed to decode even after original path replacement`(
        @TempDir root: Path
    ) {
        val path = Files.writeString(root.resolve("scene.json"), "{\"id\":\"original\"}")
        val bytes = LobbyDialogueFileSnapshot.file(path, 1024)
        Files.writeString(path, "replacement".repeat(200))
        val pin = LobbyDialogueFileSnapshot.pin(bytes)
        assertEquals(bytes.size.toLong(), pin["size"])
        assertEquals(LobbyDialogueDistributionBinding.hash(bytes), pin["sha256"])
        assertThrows(IllegalArgumentException::class.java) {
            LobbyDialogueFileSnapshot.file(path, 1024)
        }
    }
}
