package gg.grounds.minestom.lobby.scene

import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.scene.format.*
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class QueueNpcSceneMigrationTest {
    @TempDir lateinit var directory: Path
    private val assets = GroundsAssetCatalog.catalog

    private fun original() =
        lobbySceneFixture(assets, Vec3(20.0, 140.0, 20.0), Vec3(22.0, 140.0, 20.0))

    @Test
    fun `the migration writer creates a new sidecar and never overwrites existing output`() {
        val input = directory.resolve("input.json")
        val output = directory.resolve("scene-migrated.json")
        val encoded = SceneJson.encode(original()) as SceneEncodeResult.Success
        Files.write(input, encoded.bytes)
        writeQueueNpcScene(input, output, guideKey)
        val bytes = Files.readAllBytes(output)
        val scene = (SceneJson.decode(bytes) as SceneDecodeResult.Success).scene
        assertEquals(8, scene.elements.size)
        assertThrows(FileAlreadyExistsException::class.java) {
            writeQueueNpcScene(input, output, guideKey)
        }
        assertArrayEquals(bytes, Files.readAllBytes(output))
        assertArrayEquals(encoded.bytes, Files.readAllBytes(input))
        val link = directory.resolve("link.json")
        Files.createSymbolicLink(link, input)
        assertThrows(IllegalArgumentException::class.java) {
            writeQueueNpcScene(link, directory.resolve("unexpected.json"), guideKey)
        }
    }

    @Test
    fun `source idle and greeting clips become initial animation and a proximity binding`() {
        val old = assets.assets.getValue(guideKey)
        val animatedAssets =
            AssetCatalog(
                assets.id,
                assets.version,
                assets.resourcePackCompatibility,
                assets.assets +
                    (guideKey to
                        AssetDefinition(
                            guideKey,
                            AssetKind.NPC_BODY,
                            setOf(LocalId("idle"), LocalId("wave")),
                            old.defaultBounds,
                            old.editorMetadata,
                        )),
            )
        val migrated =
            migrateQueueNpcScene(
                original(),
                animatedAssets,
                guideKey,
                LocalId("idle"),
                LocalId("wave"),
            )
        for (npc in migrated.elements.drop(2).filterIsInstance<Npc>()) {
            assertEquals(LocalId("idle"), npc.initialAnimation)
            assertEquals(ProximitySensor(4.0, 5.0), npc.proximity)
            val greeting = npc.bindings.single { it.trigger == SceneTrigger.PROXIMITY_ENTER }
            assertEquals(5000, greeting.cooldownMillis)
            assertEquals(
                StartAnimationAction(ElementTarget(npc.id, null), LocalId("wave")),
                greeting.actions.single(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            migrateQueueNpcScene(
                original(),
                animatedAssets,
                guideKey,
                LocalId("not-shipped"),
                LocalId("wave"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            migrateQueueNpcScene(original(), animatedAssets, guideKey)
        }
    }

    @Test
    fun `migration preserves the scene and adds six NPCs at the original locations`() {
        val original = original()
        val migrated = migrateQueueNpcScene(original, assets, guideKey)
        assertEquals(original.id, migrated.id)
        assertEquals(original.metadata, migrated.metadata)
        assertEquals(original.elements, migrated.elements.take(2))
        assertEquals("3", migrated.catalogs.actions.version)
        val npcs = migrated.elements.drop(2).filterIsInstance<Npc>()
        assertEquals(6, npcs.size)
        assertEquals(
            listOf(-7.5, -6.5, -4.5, -2.5, -0.5, 0.5),
            npcs.map { it.transform.position.x },
        )
        assertEquals(
            listOf(73.5, 71.5, 70.5, 70.5, 71.5, 73.5),
            npcs.map { it.transform.position.z },
        )
        assertTrue(npcs.all { it.transform.position.y == 140.0 })
        assertTrue(
            npcs.all { it.transform.rotation.pitch == 0.0 },
            "Queue model bodies stay upright",
        )
        // Scene yaw rotates +Z towards +X; Minestom's entity yaw uses the opposite sign.
        listOf(9.89, 6.74, 2.24, -2.56, -7.21, -10.06).zip(npcs).forEach { (yaw, npc) ->
            assertEquals(yaw, npc.transform.rotation.yaw, 1e-9)
        }
        val actions =
            npcs.map {
                it.bindings.single { b -> b.trigger == SceneTrigger.RIGHT_CLICK }.actions.single()
                    as ApplicationAction
            }
        assertEquals("grounds:lobby/open_duel_menu", actions.first().key.value)
        assertEquals(
            listOf(
                "bedwars-2x1",
                "bedwars-squads",
                "keystone-2x1",
                "getdown",
                "survivalgames-solo",
            ),
            actions.drop(1).map { (it.arguments.getValue(LocalId("mode")) as StringArgument).value },
        )
        assertTrue(
            SceneValidation.validateCatalogs(
                    migrated,
                    assets,
                    gg.grounds.lobby.scene.LobbySceneCatalogs.CURRENT,
                )
                .isValid
        )
        assertEquals(migrated, migrateQueueNpcScene(migrated, assets, guideKey))
    }

    @Test
    fun `conflicting IDs and missing model assets are rejected instead of overwritten`() {
        val original = original()
        val collision =
            Prop(
                LocalId("queue-duels"),
                null,
                original.elements.first().transform,
                asset = markerKey,
                initialAnimation = null,
            )
        assertThrows(IllegalArgumentException::class.java) {
            migrateQueueNpcScene(
                original.with(elements = original.elements + collision),
                assets,
                guideKey,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            migrateQueueNpcScene(original, assets, AssetKey("grounds:missing"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            migrateQueueNpcScene(original, assets, markerKey)
        }
    }
}
