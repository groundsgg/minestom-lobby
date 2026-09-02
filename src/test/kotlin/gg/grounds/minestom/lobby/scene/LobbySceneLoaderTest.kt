package gg.grounds.minestom.lobby.scene

import gg.grounds.lobby.scene.LobbySceneCatalogs
import gg.grounds.minestom.lobby.LoadedLobbyMap
import gg.grounds.minestom.lobby.LobbyMapSource
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LobbySceneLoaderTest {
    @TempDir lateinit var root: Path
    private val assets = GroundsAssetCatalog.catalog

    private fun fixture() = lobbySceneFixture(assets, Vec3(11.0, 62.0, -4.0), Vec3(16.0, 63.0, 8.0))

    private fun map() = LoadedLobbyMap(root, LobbyMapSource.Local(root))

    private fun write(scene: SceneDocument = fixture()) {
        val encoded = SceneJson.encode(scene)
        assertInstanceOf(SceneEncodeResult.Success::class.java, encoded)
        Files.write(root.resolve("scene.json"), (encoded as SceneEncodeResult.Success).bytes)
    }

    private fun rejected(loader: LobbySceneLoader = LobbySceneLoader()): String =
        assertThrows(IllegalStateException::class.java) { loader.load(map()) }.message.orEmpty()

    @Test
    fun `absent root scene leaves admission unchanged and nested scene is ignored`() {
        assertNull(LobbySceneLoader().load(map()))
        Files.createDirectories(root.resolve("region"))
        Files.writeString(root.resolve("region/scene.json"), "invalid")
        assertNull(LobbySceneLoader().load(map()))
    }

    @Test
    fun `malformed scene is fatal with source diagnostics`() {
        Files.writeString(root.resolve("scene.json"), "{ broken")
        assertTrue(rejected().contains("scene.json"))
    }

    @Test
    fun `oversized scene is rejected at the bounded read boundary`() {
        Files.write(root.resolve("scene.json"), ByteArray(16 * 1024 * 1024 + 1) { 32 })
        assertTrue(rejected().contains("16 MiB"))
    }

    @Test
    fun `symlink scene including dangling links cannot masquerade as absent`() {
        val target = root.resolve("elsewhere.json")
        Files.createSymbolicLink(root.resolve("scene.json"), target)
        assertTrue(rejected().contains("regular"))
        Files.writeString(target, "{}")
        assertTrue(rejected().contains("regular"))
    }

    @Test
    fun `directory scene is rejected before attempting to read`() {
        Files.createDirectory(root.resolve("scene.json"))
        assertTrue(rejected().contains("regular"))
    }

    @Test
    fun `valid exact pins preserve selected published map identity`() {
        write()
        val prepared =
            assertNotNullScene(
                LobbySceneLoader()
                    .load(
                        LoadedLobbyMap(
                            root,
                            LobbyMapSource.Published("lobby/mainlobby", 42, "a".repeat(64)),
                        )
                    )
            )
        assertEquals("lobby/mainlobby", prepared.identity.mapId)
        assertEquals(42, prepared.identity.mapVersion)
        assertEquals("grounds:lobby-stage", prepared.identity.sceneId.value)
        assertEquals("0.6.0", prepared.assets.version)
        assertEquals("2", prepared.actions.version)
        assertEquals(
            fixture().elements.associateBy { it.id },
            prepared.scene.elements.associateBy { it.id },
        )
    }

    @Test
    fun `local scene has explicit normalized identity`() {
        write()
        val prepared = assertNotNullScene(LobbySceneLoader().load(map()))
        assertEquals("local:" + root.toAbsolutePath().normalize(), prepared.identity.mapId)
        assertEquals(0, prepared.identity.mapVersion)
    }

    @Test
    fun `catalog id and version mismatches are fatal instead of selecting latest`() {
        val valid = fixture()
        for (reference in
            listOf(
                CatalogReference(CatalogId("other:assets"), assets.version),
                CatalogReference(assets.id, "99"),
            )) {
            write(valid.with(catalogs = valid.catalogs.copy(assets = reference)))
            assertTrue(rejected().contains("catalog", ignoreCase = true))
        }
        for (reference in
            listOf(
                CatalogReference(CatalogId("other:actions"), "2"),
                CatalogReference(LobbySceneCatalogs.CURRENT.id, "99"),
            )) {
            write(valid.with(catalogs = valid.catalogs.copy(actions = reference)))
            assertTrue(rejected().contains("catalog", ignoreCase = true))
        }
    }

    @Test
    fun `legacy action pin works only without new application actions`() {
        val scene = fixture()
        write(
            scene.with(
                catalogs =
                    scene.catalogs.copy(
                        actions = CatalogReference(LobbySceneCatalogs.LEGACY.id, "1")
                    )
            )
        )
        rejected()
        write(
            scene.with(
                catalogs =
                    scene.catalogs.copy(
                        actions = CatalogReference(LobbySceneCatalogs.LEGACY.id, "1")
                    ),
                elements = scene.elements.filterIsInstance<Prop>(),
            )
        )
        assertEquals("1", assertNotNullScene(LobbySceneLoader().load(map())).actions.version)
    }

    @Test
    fun `unknown application action is rejected by real catalog validation`() {
        write(withAction(ApplicationAction(ActionKey("grounds:lobby/unknown"), emptyMap())))
        assertTrue(rejected().contains("grounds:lobby/unknown"))
    }

    @Test
    fun `missing renderer is rejected without invoking any factories`() {
        write()
        var lookups = 0
        val registry = SceneAssetRendererRegistry { _, _ ->
            lookups++
            null
        }
        assertTrue(
            rejected(LobbySceneLoader(assets, registry)).contains("renderer", ignoreCase = true)
        )
        assertTrue(lookups > 0)
        val factory = SceneAssetRendererFactory { error("Preflight must not create entities") }
        val prepared =
            assertNotNullScene(
                LobbySceneLoader(assets, SceneAssetRendererRegistry { _, _ -> factory }).load(map())
            )
        assertSame(factory, prepared.renderers.rendererFor(guideKey, AssetKind.NPC_BODY))
    }

    @Test
    fun `viewer scale requests are rejected while authored scale remains supported`() {
        write(withAction(SetViewerScaleAction(ElementTarget(LocalId("navigator"), null), 1.5, 0)))
        assertTrue(rejected().contains("viewer scale", ignoreCase = true))
        val scene = fixture()
        val marker = scene.elements.filterIsInstance<Prop>().single()
        write(
            scene.with(
                elements =
                    listOf(
                        marker.copy(transform = marker.transform.copy(scale = Vec3(2.0, 3.0, 4.0)))
                    )
            )
        )
        assertNotNull(LobbySceneLoader().load(map()))
    }

    @Test
    fun `animation stop is rejected even without an animation catalog reference`() {
        write(withAction(StopAnimationAction(ElementTarget(LocalId("navigator"), null), null)))
        assertTrue(rejected().contains("animation", ignoreCase = true))
    }

    @Test
    fun `catalog valid sound and particle capabilities are still explicitly unsupported`() {
        val sound = AssetKey("grounds:test/sound")
        val particle = AssetKey("grounds:test/particle")
        val extended =
            AssetCatalog(
                assets.id,
                assets.version,
                assets.resourcePackCompatibility,
                assets.assets +
                    mapOf(
                        sound to
                            AssetDefinition(sound, AssetKind.SOUND, emptySet(), null, emptyMap()),
                        particle to
                            AssetDefinition(
                                particle,
                                AssetKind.PARTICLE,
                                emptySet(),
                                null,
                                emptyMap(),
                            ),
                    ),
            )
        write(withAction(PlaySoundAction(sound, 1.0, 1.0)))
        assertTrue(rejected(LobbySceneLoader(extended)).contains("sound", ignoreCase = true))
        write(
            withAction(
                EmitParticleAction(
                    ElementTarget(LocalId("navigator"), null),
                    particle,
                    1,
                    ORIGIN,
                    0.0,
                )
            )
        )
        assertTrue(rejected(LobbySceneLoader(extended)).contains("particle", ignoreCase = true))
    }

    private fun withAction(action: SceneAction): SceneDocument {
        val scene = fixture()
        val npc = scene.elements.filterIsInstance<Npc>().single()
        return scene.with(
            elements =
                listOf(
                    npc.with(
                        bindings =
                            listOf(
                                TriggerBinding(
                                    SceneTrigger.RIGHT_CLICK,
                                    emptyList(),
                                    0,
                                    0,
                                    listOf(action),
                                )
                            )
                    )
                )
        )
    }

    private fun assertNotNullScene(value: PreparedLobbyScene?): PreparedLobbyScene {
        assertNotNull(value)
        return value!!
    }
}
