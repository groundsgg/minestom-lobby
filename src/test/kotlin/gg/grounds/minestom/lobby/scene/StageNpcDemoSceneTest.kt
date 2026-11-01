package gg.grounds.minestom.lobby.scene

import gg.grounds.minestom.lobby.LoadedLobbyMap
import gg.grounds.minestom.lobby.LobbyMapSource
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.scene.format.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StageNpcDemoSceneTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `complete demo preserves foreign elements covers every NPC feature and resolves in the real host`() {
        val assets = GroundsAssetCatalog.catalog
        val before = lobbySceneFixture(assets, Vec3(100.0, 140.0, 100.0), Vec3(102.0, 140.0, 100.0))
        val scene = stageNpcDemoScene(before, assets)
        assertEquals(scene, stageNpcDemoScene(scene, assets))
        assertTrue(scene.elements.containsAll(before.elements))
        val demos =
            scene.elements.filterIsInstance<Npc>().filter { it.id.value.startsWith("demo-") }
        assertEquals(15, demos.size)
        assertEquals(15, demos.map { it.body }.toSet().size)
        assertTrue(demos.all { it.transform.position.y == 173.0 })
        assertEquals(
            6,
            scene.elements.filterIsInstance<Npc>().count { it.id.value.startsWith("queue-") },
        )
        assertEquals(
            SceneTrigger.entries.toSet(),
            demos.flatMap { it.bindings }.map { it.trigger }.toSet(),
        )
        val conditions = demos.flatMap { it.bindings }.flatMap { it.conditions }
        assertTrue(conditions.any { it is SneakingCondition })
        assertTrue(
            conditions.any { it is PermissionCondition && it.permission == "grounds.scene.edit" }
        )
        assertEquals(
            setOf(SceneHand.MAIN, SceneHand.OFF),
            conditions.filterIsInstance<HandCondition>().map { it.hand }.toSet(),
        )
        assertEquals(
            setOf(SceneGameMode.ADVENTURE, SceneGameMode.CREATIVE),
            conditions.filterIsInstance<GameModeCondition>().map { it.gameMode }.toSet(),
        )
        assertTrue(demos.any { it.look == LookBehavior.Fixed })
        assertTrue(demos.any { (it.look as? LookBehavior.TrackNearest)?.yawOnly == true })
        assertTrue(demos.any { (it.look as? LookBehavior.TrackNearest)?.yawOnly == false })
        assertEquals(NpcCollisionMode.entries.toSet(), demos.map { it.collision.mode }.toSet())
        val actions = demos.flatMap { it.bindings }.flatMap { it.actions }
        assertTrue(actions.any { it is StartAnimationAction })
        assertTrue(actions.any { it is StopAnimationAction })
        assertTrue(actions.any { it is SetViewerScaleAction && it.transitionMillis > 0 })
        assertTrue(actions.any { it is SetViewerHighlightAction })
        assertTrue(actions.any { it is PlaySoundAction })
        assertTrue(actions.any { it is EmitParticleAction })
        assertTrue(actions.any { it is SendMessageAction })
        assertTrue(actions.any { it is SendActionBarAction })
        assertTrue(actions.any { it is ShowTitleAction })
        assertTrue(demos.flatMap { it.bindings }.any { it.cooldownMillis > 0 })
        assertTrue(demos.flatMap { it.bindings }.any { it.debounceMillis > 0 })
        assertTrue(
            demos.flatMap { it.bindings }.any { it.debounceMillis > 0 && it.cooldownMillis == 0L },
            "Debounce must be independently demonstrable",
        )
        val encoded = SceneJson.encode(scene) as SceneEncodeResult.Success
        Files.write(directory.resolve("scene.json"), encoded.bytes)
        assertNotNull(
            LobbySceneLoader().load(LoadedLobbyMap(directory, LobbyMapSource.Local(directory)))
        )
    }
}
