package gg.grounds.minestom.lobby.scene

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat

/** Linux application-loader qualification; a pathname alone cannot identify an opened JVM JAR. */
internal object LobbyDialogueExecutableIdentity {
    fun requireOpened(executable: Path, expectedPin: Map<String, Any>, classes: List<Class<*>>) {
        val loader = ClassLoader.getSystemClassLoader()
        require(loader.javaClass.name == "jdk.internal.loader.ClassLoaders\$AppClassLoader")
        classes.forEach { type ->
            require(type.classLoader === loader)
            val origin = Path.of(requireNotNull(type.protectionDomain.codeSource).location.toURI())
            require(Files.isSameFile(origin, executable))
        }
        val realPath = executable.toRealPath().toString()
        val descriptors =
            Files.list(Path.of("/proc/self/fd")).use { entries ->
                entries
                    .filter { entry ->
                        val target =
                            try {
                                Files.readSymbolicLink(entry).toString()
                            } catch (_: java.nio.file.NoSuchFileException) {
                                return@filter false
                            }
                        target == realPath || target == "$realPath (deleted)"
                    }
                    .toList()
            }
        // No new ZipFile may be opened before this check: the handle must already exist in the JVM.
        val descriptor = descriptors.single()
        require(Files.isSameFile(descriptor, executable))
        val before = Files.readAttributes(descriptor, BasicFileAttributes::class.java)
        require(before.isRegularFile && before.size() == expectedPin["size"])
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        Files.newInputStream(descriptor).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val remaining = before.size() - count
                val maximum = if (remaining >= buffer.size) buffer.size else (remaining + 1).toInt()
                val read = input.read(buffer, 0, maximum)
                if (read < 0) break
                count += read
                require(count <= before.size())
                digest.update(buffer, 0, read)
            }
        }
        val after = Files.readAttributes(descriptor, BasicFileAttributes::class.java)
        require(
            count == before.size() &&
                before.fileKey() == after.fileKey() &&
                before.lastModifiedTime() == after.lastModifiedTime() &&
                before.size() == after.size()
        )
        require(HexFormat.of().formatHex(digest.digest()) == expectedPin["sha256"])
        require(Files.isSameFile(descriptor, executable))
    }
}
