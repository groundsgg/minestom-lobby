package gg.grounds.minestom.lobby

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LobbyLodPilotTest {
    private val canonical = "a".repeat(64)
    private val input = "b".repeat(64)
    private val registry = "c".repeat(64)
    private val generator = "d".repeat(64)

    private fun env(file: Path) =
        mapOf(
            "GROUNDS_LOD_PILOT_ENABLED" to "true",
            "GROUNDS_LOD_PILOT_RUN_ID" to UUID.randomUUID().toString(),
            "GROUNDS_LOD_PILOT_PLAYERS" to UUID.randomUUID().toString(),
            "GROUNDS_LOD_PILOT_GENERATOR_SHA256" to generator,
            "GROUNDS_LOD_PILOT_CAPABILITY_FILE" to file.toString(),
            "MAPS_ENVIRONMENT" to "stage",
        )

    private fun loaded(root: Path) =
        LoadedLobbyMap(
            root,
            LobbyMapSource.Published("lobby/mainlobby", 13, canonical),
            transportSha256 = input,
            prelit = true,
            environment = "stage",
        )

    @Test
    fun `disabled pilot never reads a secret or evaluates host registry`(@TempDir root: Path) {
        val config = env(root.resolve("missing-secret")).minus("GROUNDS_LOD_PILOT_ENABLED")
        assertNull(LobbyLodPilotSettings.fromEnvironment(config))
        assertNull(
            LobbyLodPilot.configure(config, loaded(root), { error("registry must not run") }) { _, _
                ->
                error("install must not run")
            }
        )
    }

    @Test
    fun `only exact published prelit stage lobby can bind a pilot`(@TempDir root: Path) {
        val config = env(root.resolve("capability"))
        val settings = assertNotNull(LobbyLodPilotSettings.fromEnvironment(config))
        val selected = loaded(root)
        val expectation = assertNotNull(settings.expectation(selected, registry))
        assertEquals(canonical, expectation.mapSha256)
        assertEquals(input, expectation.sourceSha256)
        assertEquals(generator, expectation.generatorSha256)
        assertEquals(registry, expectation.registrySha256)
        for (bad in
            listOf(
                selected.copy(prelit = false),
                selected.copy(environment = "production"),
                selected.copy(transportSha256 = null),
                selected.copy(source = LobbyMapSource.Local(root)),
                selected.copy(source = LobbyMapSource.Published("lobby/other", 13, canonical)),
                selected.copy(source = LobbyMapSource.Published("lobby/mainlobby", 14, canonical)),
            )) {
            assertNull(settings.expectation(bad, registry))
        }
        assertFails {
            LobbyLodPilotSettings.fromEnvironment(config + ("MAPS_ENVIRONMENT" to "production"))
        }
    }

    @Test
    fun `explicit current map version replaces the historical pilot selection`(
        @TempDir root: Path
    ) {
        val config =
            env(root.resolve("capability")) +
                mapOf(
                    "GROUNDS_LOD_PILOT_MAP_ADDRESS" to "lobby/mainlobby",
                    "GROUNDS_LOD_PILOT_MAP_VERSION" to "17",
                )
        val settings = assertNotNull(LobbyLodPilotSettings.fromEnvironment(config))
        val current =
            loaded(root).copy(source = LobbyMapSource.Published("lobby/mainlobby", 17, canonical))
        val expected = assertNotNull(settings.expectation(current, registry))
        assertEquals(canonical, expected.mapSha256)
        assertEquals(input, expected.sourceSha256)
        assertNull(settings.expectation(loaded(root), registry))
        assertNull(
            settings.expectation(
                current.copy(source = LobbyMapSource.Published("lobby/other", 17, canonical)),
                registry,
            )
        )
    }

    @Test
    fun `partial or malformed map selection fails closed`(@TempDir root: Path) {
        val config = env(root.resolve("capability"))
        for (selection in
            listOf(
                mapOf("GROUNDS_LOD_PILOT_MAP_VERSION" to "17"),
                mapOf("GROUNDS_LOD_PILOT_MAP_ADDRESS" to "lobby/mainlobby"),
                mapOf(
                    "GROUNDS_LOD_PILOT_MAP_ADDRESS" to "lobby/mainlobby",
                    "GROUNDS_LOD_PILOT_MAP_VERSION" to "0",
                ),
                mapOf(
                    "GROUNDS_LOD_PILOT_MAP_ADDRESS" to "../other",
                    "GROUNDS_LOD_PILOT_MAP_VERSION" to "17",
                ),
                mapOf(
                    "GROUNDS_LOD_PILOT_MAP_ADDRESS" to "u/creator/private",
                    "GROUNDS_LOD_PILOT_MAP_VERSION" to "17",
                ),
            )) {
            assertFails { LobbyLodPilotSettings.fromEnvironment(config + selection) }
        }
    }

    @Test
    fun `invalid configuration or optional adapter failure cannot break lobby installation`(
        @TempDir root: Path
    ) {
        val config = env(root.resolve("capability"))
        for (bad in
            listOf(
                config + ("GROUNDS_LOD_PILOT_RUN_ID" to "wrong"),
                config +
                    ("GROUNDS_LOD_PILOT_PLAYERS" to
                        (1..6).joinToString(",") { UUID.randomUUID().toString() }),
                config + ("GROUNDS_LOD_PILOT_SERVICE_ORIGIN" to "http://wrong.test"),
            )) {
            assertNull(
                LobbyLodPilot.configure(bad, loaded(root), { registry }) { _, _ ->
                    error("bad config reached installer")
                }
            )
        }
        var called = false
        assertNull(
            LobbyLodPilot.configure(config, loaded(root), { registry }) { _, _ ->
                called = true
                throw java.io.IOException("test-only adapter failure")
            }
        )
        assertTrue(called)
    }

    @Test
    fun `capability file is bounded and configuration never prints its contents`(
        @TempDir root: Path
    ) {
        val file = root.resolve("capability")
        val token = "test-capability-" + "x".repeat(48)
        Files.writeString(file, token + "\n")
        val settings = assertNotNull(LobbyLodPilotSettings.fromEnvironment(env(file)))
        assertEquals(token, settings.readCapability())
        assertFalse(settings.toString().contains(token))
        Files.writeString(file, "x".repeat(1024))
        assertFails { settings.readCapability() }
    }
}
