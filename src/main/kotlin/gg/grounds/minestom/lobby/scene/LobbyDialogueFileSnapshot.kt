package gg.grounds.minestom.lobby.scene

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Bounds the actual read, including files that grow after the initial stat. */
internal object LobbyDialogueFileSnapshot {
    fun read(input: InputStream, limit: Int): ByteArray =
        input.readNBytes(limit + 1).also {
            require(it.size <= limit) { "File snapshot exceeds byte limit" }
        }

    fun file(path: Path, limit: Int): ByteArray {
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(before.isRegularFile && before.size() <= limit)
        val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { read(it, limit) }
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(
            after.isRegularFile &&
                bytes.size.toLong() == before.size() &&
                before.size() == after.size() &&
                before.fileKey() == after.fileKey() &&
                before.lastModifiedTime() == after.lastModifiedTime()
        ) {
            "File changed while reading snapshot"
        }
        return bytes
    }

    fun pin(bytes: ByteArray): Map<String, Any> =
        mapOf(
            "sha256" to LobbyDialogueDistributionBinding.hash(bytes),
            "size" to bytes.size.toLong(),
        )
}
