package gg.grounds.minestom.lobby

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LobbyLodServingTest {
    private val canonical = "a".repeat(64)
    private val input = "b".repeat(64)
    private val registry = "c".repeat(64)
    private val generator = "d".repeat(64)

    private fun env(file: Path) =
        mapOf(
            "GROUNDS_LOD_SERVING_ENABLED" to "true",
            "GROUNDS_LOD_SERVING_GENERATOR_SHA256" to generator,
            "GROUNDS_LOD_SERVING_CAPABILITY_FILE" to file.toString(),
            "MAPS_ENVIRONMENT" to "stage",
        )

    private fun loaded(root: Path, version: Long = 18) =
        LoadedLobbyMap(
            root,
            LobbyMapSource.Published("lobby/mainlobby", version, canonical),
            transportSha256 = input,
            prelit = true,
            environment = "stage",
        )

    @Test
    fun `disabled serving does not read a secret or consult registry`(@TempDir root: Path) {
        val config = env(root.resolve("missing-secret")) - "GROUNDS_LOD_SERVING_ENABLED"
        assertNull(LobbyLodServingSettings.fromEnvironment(config))
        assertNull(
            LobbyLodServing.configure(
                config,
                loaded(root),
                { error("unexpected registry lookup") },
            ) { _, _ ->
                error("unexpected installation")
            }
        )
    }

    @Test
    fun `entitled serving follows exact actual loaded map without hardcoded version or UUID`(
        @TempDir root: Path
    ) {
        val settings =
            assertNotNull(LobbyLodServingSettings.fromEnvironment(env(root.resolve("capability"))))
        for (version in listOf(18L, 19L)) {
            val expected = assertNotNull(settings.expectation(loaded(root, version), registry))
            assertEquals(canonical, expected.mapSha256)
            assertEquals(input, expected.sourceSha256)
            assertEquals(registry, expected.registrySha256)
            assertEquals(generator, expected.generatorSha256)
        }
    }

    @Test
    fun `untrusted fallback other map and non stage sources never bind`(@TempDir root: Path) {
        val settings =
            assertNotNull(LobbyLodServingSettings.fromEnvironment(env(root.resolve("capability"))))
        val selected = loaded(root)
        for (bad in
            listOf(
                selected.copy(prelit = false),
                selected.copy(environment = "production"),
                selected.copy(transportSha256 = null),
                selected.copy(source = LobbyMapSource.Local(root)),
                selected.copy(source = LobbyMapSource.Published("lobby/other", 18, canonical)),
                selected.copy(source = LobbyMapSource.Published("u/author/map", 18, canonical)),
                selected.copy(source = LobbyMapSource.Published("lobby/mainlobby", 18, "bad")),
            )) assertNull(settings.expectation(bad, registry))
    }

    @Test
    fun `configuration refuses pilot overlap invalid origin generator and relative cache`(
        @TempDir root: Path
    ) {
        val valid = env(root.resolve("capability"))
        for (bad in
            listOf(
                valid + ("GROUNDS_LOD_PILOT_ENABLED" to "true"),
                valid + ("MAPS_ENVIRONMENT" to "prod"),
                valid + ("GROUNDS_LOD_SERVING_GENERATOR_SHA256" to "bad"),
                valid + ("GROUNDS_LOD_SERVING_SERVICE_ORIGIN" to "http://api.grounds.gg"),
                valid +
                    ("GROUNDS_LOD_SERVING_SERVICE_ORIGIN" to "https://user:pass@api.grounds.gg"),
                valid + ("GROUNDS_LOD_SERVING_SERVICE_ORIGIN" to "https://api.grounds.gg/path"),
                valid + ("GROUNDS_LOD_SERVING_CACHE_DIR" to "relative"),
            )) assertFails { LobbyLodServingSettings.fromEnvironment(bad) }
    }

    @Test
    fun `valid capability is private bounded and installation remains optional`(
        @TempDir root: Path
    ) {
        val file = root.resolve("capability")
        Files.writeString(file, "s".repeat(43) + "\n")
        val config = env(file)
        val settings = assertNotNull(LobbyLodServingSettings.fromEnvironment(config))
        assertEquals("s".repeat(43), settings.readCapability())
        var installed = false
        var closed = false
        val handle =
            assertNotNull(
                LobbyLodServing.configure(config, loaded(root), { registry }) { _, expected ->
                    assertEquals(input, expected.sourceSha256)
                    installed = true
                    AutoCloseable { closed = true }
                }
            )
        assertTrue(installed)
        handle.close()
        assertTrue(closed)
        assertNull(
            LobbyLodServing.configure(config, loaded(root), { registry }) { _, _ ->
                error("control unavailable")
            }
        )
        for (bad in listOf("tiny", "s".repeat(259), "s".repeat(32) + " secret")) {
            Files.writeString(file, bad)
            assertFails { settings.readCapability() }
        }
    }

    @Test
    fun `chart secret reference can supply capability without a plaintext values file`(
        @TempDir root: Path
    ) {
        val config =
            (env(root.resolve("unused")) - "GROUNDS_LOD_SERVING_CAPABILITY_FILE") +
                ("GROUNDS_LOD_SERVING_CAPABILITY" to "s".repeat(43))
        assertEquals(
            "s".repeat(43),
            assertNotNull(LobbyLodServingSettings.fromEnvironment(config)).readCapability(),
        )
        assertFails {
            LobbyLodServingSettings.fromEnvironment(
                config + ("GROUNDS_LOD_SERVING_CAPABILITY_FILE" to root.resolve("other").toString())
            )
        }
    }
}
