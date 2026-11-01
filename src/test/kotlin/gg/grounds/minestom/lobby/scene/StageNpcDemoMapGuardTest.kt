package gg.grounds.minestom.lobby.scene

import gg.grounds.minestom.lobby.*
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.scene.format.Vec3
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StageNpcDemoMapGuardTest {
    @Test
    fun `demo never becomes ready on a baked fallback wrong pin or missing scene`() {
        val assets = GroundsAssetCatalog.catalog
        val base = lobbySceneFixture(assets, Vec3(100.0, 140.0, 100.0), Vec3(102.0, 140.0, 100.0))
        val scene = stageNpcDemoScene(base, assets)
        val digest = "a".repeat(64)
        assertThrows(IllegalArgumentException::class.java) {
            requireStageNpcDemoMap(LobbyMapSource.Local(Path.of("fallback")), scene, digest)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireStageNpcDemoMap(
                LobbyMapSource.Published("lobby/mainlobby", 6, "b".repeat(64)),
                scene,
                digest,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireStageNpcDemoMap(
                LobbyMapSource.Published("lobby/mainlobby", 6, digest),
                null,
                digest,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireStageNpcDemoMap(
                LobbyMapSource.Published("lobby/mainlobby", 6, digest),
                base,
                digest,
            )
        }
        requireStageNpcDemoMap(
            LobbyMapSource.Published("lobby/mainlobby", 6, digest),
            scene,
            digest,
        )
    }
}
