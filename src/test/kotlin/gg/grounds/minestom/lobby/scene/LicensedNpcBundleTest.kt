package gg.grounds.minestom.lobby.scene

import com.google.gson.JsonParser
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.resourcepacks.npc.*
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.SceneAssetRenderContext
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LicensedNpcBundleTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `chosen models attach their real parts animate and clean up in Minestom`() {
        val assets = GroundsAssetCatalog.catalog
        val body = AssetKey("grounds:npc/townfolk/builder")
        assumeTrue(body in assets.assets, "Licensed model variant not selected")
        net.minestom.server.MinecraftServer.init()
        val scene =
            migrateGroundsQueueNpcScene(
                lobbySceneFixture(assets, Vec3(30.0, 140.0, 30.0), Vec3(32.0, 140.0, 30.0)),
                assets,
            )
        val renderers = LobbyNpcModelRenderers(assets)
        for (npc in
            scene.elements.filterIsInstance<Npc>().filter { it.id.value.startsWith("queue-") }) {
            RendererFixture().use { fixture ->
                fixture.player()
                val handle =
                    renderers
                        .rendererFor(npc.body, AssetKind.NPC_BODY)!!
                        .create(
                            SceneAssetRenderContext(
                                fixture.instance,
                                npc.id,
                                null,
                                npc.body,
                                sceneTransform(),
                            )
                        )
                        .toCompletableFuture()
                        .join()
                val displays =
                    fixture.instance.entities.filter {
                        it.entityType == net.minestom.server.entity.EntityType.ITEM_DISPLAY
                    }
                assertTrue(displays.isNotEmpty())
                for (clip in assets.assets.getValue(npc.body).animations) {
                    handle.startAnimation(clip, 0)
                    for (time in listOf(250L, 500L, 1000L, 20000L, 20500L)) {
                        handle.advanceAnimation(time)
                        assertTrue(
                            displays.all {
                                val meta =
                                    it.entityMeta
                                        as
                                        net.minestom.server.entity.metadata.display.ItemDisplayMeta
                                meta.leftRotation.all(Float::isFinite) &&
                                    meta.rightRotation.all(Float::isFinite) &&
                                    meta.scale.length().isFinite()
                            }
                        )
                    }
                }
                handle.close()
                assertTrue(displays.all { it.isRemoved })
            }
        }
    }

    @Test
    fun `the real purchased model archive prepares an animated scene with catalogue exact resources`() {
        val assets = GroundsAssetCatalog.catalog
        val body = AssetKey("grounds:npc/townfolk/builder")
        assumeTrue(body in assets.assets, "Licensed model variant not selected")
        val scene =
            migrateGroundsQueueNpcScene(
                lobbySceneFixture(assets, Vec3(30.0, 140.0, 30.0), Vec3(32.0, 140.0, 30.0)),
                assets,
            )
        val expected =
            mapOf(
                "queue-duels" to "guildfolk/forge_master",
                "queue-bedwars-2x1" to "craftmasters/shipman",
                "queue-bedwars-squads" to "craftmasters/shipman",
                "queue-keystone-2x1" to "townfolk/builder",
                "queue-getdown" to "townfolk/ragpicker",
                "queue-survivalgames-solo" to "guildfolk/crystal_prospector",
            )
        for ((id, path) in expected) {
            val npc = scene.elements.filterIsInstance<Npc>().single { it.id.value == id }
            assertEquals(AssetKey("grounds:npc/$path"), npc.body)
            assertEquals(LocalId("idle"), npc.initialAnimation)
            val greeting = npc.bindings.single { it.trigger == SceneTrigger.PROXIMITY_ENTER }
            assertEquals(15000, greeting.cooldownMillis)
            assertEquals(
                LocalId(if (path.startsWith("guildfolk/")) "hello" else "wave"),
                (greeting.actions.single() as StartAnimationAction).animation,
            )
        }
        val encoded = SceneJson.encode(scene) as SceneEncodeResult.Success
        Files.write(directory.resolve("scene.json"), encoded.bytes)
        val prepared =
            LobbySceneLoader()
                .load(
                    gg.grounds.minestom.lobby.LoadedLobbyMap(
                        directory,
                        gg.grounds.minestom.lobby.LobbyMapSource.Local(directory),
                    )
                )
        assertNotNull(prepared)
        assertNotNull(prepared!!.renderers.rendererFor(body, AssetKind.NPC_BODY))
    }

    @Test
    fun `all fifteen original models and all forty five original clips produce finite poses`() {
        val index =
            GroundsAssetCatalog::class
                .java
                .getResourceAsStream("/gg/grounds/resourcepacks/npc/index.json")
                ?.bufferedReader()
                ?.use { it.readText() }
        assumeTrue(index != null)
        val entries = JsonParser.parseString(index).asJsonObject.getAsJsonArray("models")
        assumeTrue(entries.size() > 0, "Licensed model variant not selected")
        assertEquals(15, entries.size())
        var clips = 0
        for (entry in entries) {
            val path = entry.asJsonObject.get("resource").asString
            val model =
                NpcModel.parse(
                    GroundsAssetCatalog::class
                        .java
                        .getResourceAsStream(path)!!
                        .bufferedReader()
                        .use { it.readText() }
                )
            clips += model.animations.size
            for ((name, clip) in model.animations) {
                for (fraction in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
                    assertTrue(
                        model.pose(name, clip.length * fraction).all { pose ->
                            pose.values.all(Double::isFinite)
                        },
                        "$path $name $fraction",
                    )
                }
            }
        }
        assertEquals(45, clips)
    }
}
