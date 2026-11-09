package gg.grounds.minestom.lobby.scene

import gg.grounds.minestom.lobby.*
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.scene.format.Vec3
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class StageNpcDemoMapGuardTest {
    @Test
    fun `a new verified published map version needs no per-map image binding`() {
        val base =
            lobbySceneFixture(
                GroundsAssetCatalog.catalog,
                Vec3(100.0, 140.0, 100.0),
                Vec3(102.0, 140.0, 100.0),
            )
        val elements =
            (0..15).map { index ->
                gg.grounds.scene.format.Npc(
                    gg.grounds.scene.format.LocalId(
                        if (index == 15) "sensor-demo-entry" else "demo-$index"
                    ),
                    null,
                    gg.grounds.scene.format.Transform(
                        Vec3(0.0, 0.0, 0.0),
                        gg.grounds.scene.format.ZERO_ROTATION,
                        Vec3(1.0, 1.0, 1.0),
                    ),
                    body = gg.grounds.scene.format.AssetKey("grounds:editor/guide"),
                    label = null,
                    labelOffset = Vec3(0.0, 0.0, 0.0),
                    look = gg.grounds.scene.format.LookBehavior.Fixed,
                    initialAnimation = null,
                    interactionBounds =
                        gg.grounds.scene.format.LocalBounds(
                            Vec3(0.0, 0.0, 0.0),
                            Vec3(1.0, 1.0, 1.0),
                        ),
                    proximity = null,
                    bindings = emptyList(),
                )
            }
        val scene =
            gg.grounds.scene.format.SceneDocument(
                base.schemaVersion,
                base.id,
                base.metadata,
                base.catalogs,
                emptyList(),
                elements,
            )
        listOf("a".repeat(64), "b".repeat(64)).forEachIndexed { index, sha ->
            requireStageNpcDemoMap(
                LobbyMapSource.Published("lobby/mainlobby", index.toLong() + 16, sha),
                scene,
            )
        }
    }

    @Test
    fun `demo never becomes ready on a baked fallback wrong pin or missing scene`() {
        val assets = GroundsAssetCatalog.catalog
        assumeTrue(
            gg.grounds.scene.format.AssetKey("grounds:npc/townfolk/builder") in assets.assets,
            "Licensed model variant not selected",
        )
        val base = lobbySceneFixture(assets, Vec3(100.0, 140.0, 100.0), Vec3(102.0, 140.0, 100.0))
        val scene = stageNpcDemoScene(base, assets)
        val digest = "a".repeat(64)
        assertThrows(IllegalArgumentException::class.java) {
            requireStageNpcDemoMap(LobbyMapSource.Local(Path.of("fallback")), scene)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireStageNpcDemoMap(
                LobbyMapSource.Published("lobby/wrong", 6, "b".repeat(64)),
                scene,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireStageNpcDemoMap(LobbyMapSource.Published("lobby/mainlobby", 6, digest), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireStageNpcDemoMap(LobbyMapSource.Published("lobby/mainlobby", 6, digest), base)
        }
        requireStageNpcDemoMap(LobbyMapSource.Published("lobby/mainlobby", 6, digest), scene)
    }
}
