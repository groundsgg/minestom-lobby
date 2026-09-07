package gg.grounds.minestom.lobby.scene

import gg.grounds.lobby.scene.LobbySceneCatalogs
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.scene.format.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LobbySceneFixtureTest {
    @TempDir lateinit var project: Path

    @Test
    fun `generator creates a typed catalog-valid fixture at explicit coordinates and round trips`() {
        val output =
            generateLobbySceneFixture(
                project,
                "walkthrough.json",
                Vec3(12.0, 64.5, -7.0),
                Vec3(16.0, 65.0, 3.0),
            )
        assertEquals(project.resolve("build/fixtures/walkthrough.json"), output)
        assertTrue(Files.isRegularFile(output))
        val bytes = Files.readAllBytes(output)
        val decoded = SceneJson.decode(bytes)
        assertInstanceOf(SceneDecodeResult.Success::class.java, decoded)
        val scene = (decoded as SceneDecodeResult.Success).scene
        assertTrue(
            SceneValidation.validateCatalogs(
                    scene,
                    GroundsAssetCatalog.catalog,
                    LobbySceneCatalogs.CURRENT,
                )
                .isValid
        )
        val npc = scene.elements.filterIsInstance<Npc>().single()
        val marker = scene.elements.filterIsInstance<Prop>().single()
        assertEquals(Vec3(12.0, 64.5, -7.0), npc.transform.position)
        assertEquals(Vec3(16.0, 65.0, 3.0), marker.transform.position)
        assertEquals(AssetKey("grounds:editor/guide"), npc.body)
        assertEquals(AssetKey("grounds:editor/marker"), marker.asset)
        assertEquals(Vec3(0.0, 2.1, 0.0), npc.labelOffset)
        assertEquals(LookBehavior.Fixed, npc.look)
        assertEquals(LocalBounds(Vec3(0.0, 0.9, 0.0), Vec3(0.6, 1.8, 0.6)), npc.interactionBounds)
        val click = npc.bindings.single { it.trigger == SceneTrigger.RIGHT_CLICK }
        assertEquals(500, click.cooldownMillis)
        assertEquals(listOf(HandCondition(SceneHand.MAIN)), click.conditions)
        assertEquals(
            listOf(ApplicationAction(LobbySceneCatalogs.OPEN_NAVIGATOR, emptyMap())),
            click.actions,
        )
        assertEquals(
            listOf(SetViewerHighlightAction(ElementTarget(LocalId("navigator"), null), true, 0)),
            npc.bindings.single { it.trigger == SceneTrigger.HOVER_ENTER }.actions,
        )
        assertEquals(
            listOf(SetViewerHighlightAction(ElementTarget(LocalId("navigator"), null), false, 0)),
            npc.bindings.single { it.trigger == SceneTrigger.HOVER_LEAVE }.actions,
        )
        assertArrayEquals(bytes, (SceneJson.encode(scene) as SceneEncodeResult.Success).bytes)
        assertFalse(Files.exists(project.resolve("scene.json")))
        assertFalse(Files.exists(project.resolve("lobby")))
    }

    @Test
    fun `generator refuses an existing destination without overwriting it`() {
        val directory = Files.createDirectories(project.resolve("build/fixtures"))
        val output = Files.writeString(directory.resolve("existing.json"), "keep this")
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
            generateLobbySceneFixture(project, "existing.json", ORIGIN, ORIGIN)
        }
        assertEquals("keep this", Files.readString(output))
    }

    @Test
    fun `generator rejects traversal and absolute output names`() {
        for (name in
            listOf(
                "../scene.json",
                "nested/scene.json",
                project.resolve("scene.json").toString(),
                "scene.txt",
            )) {
            assertThrows(IllegalArgumentException::class.java) {
                generateLobbySceneFixture(project, name, ORIGIN, ORIGIN)
            }
        }
        assertFalse(Files.exists(project.resolve("scene.json")))
    }

    @Test
    fun `generator refuses symlinked output directories`() {
        val live = Files.createDirectory(project.resolve("live"))
        Files.createSymbolicLink(project.resolve("build"), live)
        assertThrows(IllegalStateException::class.java) {
            generateLobbySceneFixture(project, "scene.json", ORIGIN, ORIGIN)
        }
        assertFalse(Files.exists(live.resolve("fixtures/scene.json")))
    }
}
