package gg.grounds.minestom.lobby.scene

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LobbyDialogueExecutableIdentityTest {
    @TempDir lateinit var directory: Path

    private fun launchJar(path: Path, marker: String) {
        val manifest =
            Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes[Attributes.Name.MAIN_CLASS] =
                    LobbyExecutableIdentityProbe::class.java.name
                mainAttributes[Attributes.Name.CLASS_PATH] =
                    requireNotNull(System.getProperty("lobby.test.runtimeClasspath"))
                        .split(java.io.File.pathSeparator)
                        .joinToString(" ") { Path.of(it).toUri().toASCIIString() }
            }
        JarOutputStream(Files.newOutputStream(path), manifest).use { jar ->
            val name = LobbyExecutableIdentityProbe::class.java.name.replace('.', '/') + ".class"
            jar.putNextEntry(JarEntry(name))
            LobbyExecutableIdentityProbe::class.java.classLoader.getResourceAsStream(name)!!.use {
                it.copyTo(jar)
            }
            jar.closeEntry()
            jar.putNextEntry(JarEntry("implementation.txt"))
            jar.write(marker.toByteArray())
            jar.closeEntry()
        }
    }

    @Test
    fun `actual JVM opened archive survives unchanged and denies atomic pathname replacement`() {
        listOf(false, true).forEach { replace ->
            val jar = directory.resolve("host-$replace.jar")
            launchJar(jar, "original")
            val process =
                ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-jar",
                        jar.toString(),
                        jar.toString(),
                    )
                    .redirectErrorStream(true)
                    .start()
            try {
                val output = process.inputStream.bufferedReader()
                assertEquals("ARCHIVE_OPENED", output.readLine())
                if (replace) {
                    val replacement = directory.resolve("replacement.jar")
                    launchJar(replacement, "different implementation with same metadata")
                    Files.move(replacement, jar, ATOMIC_MOVE, REPLACE_EXISTING)
                }
                process.outputStream.bufferedWriter().use {
                    it.write("verify\n")
                    it.flush()
                }
                assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                val rest = output.readText()
                assertEquals(0, process.exitValue(), rest)
                assertTrue(
                    rest.contains(
                        if (replace) "OPENED_ARCHIVE_DENIED" else "OPENED_ARCHIVE_ACCEPTED"
                    ),
                    rest,
                )
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }
}

internal object LobbyExecutableIdentityProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val path = Path.of(arguments.single())
        println("ARCHIVE_OPENED")
        check(readln() == "verify")
        val result = runCatching {
            LobbyDialogueExecutableIdentity.requireOpened(
                path,
                LobbyDialogueDistributionBinding.pin(path),
                listOf(LobbyExecutableIdentityProbe::class.java),
            )
            LobbyExecutableIdentityProbe::class
                .java
                .getResourceAsStream("/implementation.txt")!!
                .use { it.readAllBytes() }
            java.util.zip.ZipFile(path.toFile()).use { check(it.size() > 0) }
            LobbyDialogueExecutableIdentity.requireOpened(
                path,
                LobbyDialogueDistributionBinding.pin(path),
                listOf(LobbyExecutableIdentityProbe::class.java),
            )
        }
        println(if (result.isSuccess) "OPENED_ARCHIVE_ACCEPTED" else "OPENED_ARCHIVE_DENIED")
    }
}
