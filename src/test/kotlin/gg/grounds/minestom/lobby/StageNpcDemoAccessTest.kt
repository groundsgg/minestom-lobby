package gg.grounds.minestom.lobby

import gg.grounds.minestom.lobby.scene.RendererFixture
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StageNpcDemoAccessTest {
    @Test
    fun `actual stationary server teleport restores the mode without a client movement`() {
        MinecraftServer.init()
        RendererFixture().use { fixture ->
            val admin = fixture.player(Pos(-7.0, 173.0, 17.0))
            admin.gameMode = GameMode.ADVENTURE
            val access = StageNpcDemoAccess(fixture.instance) { true }
            val node = net.minestom.server.event.EventNode.all("demo-actual-teleport")
            access.register(node)
            MinecraftServer.getGlobalEventHandler().addChild(node)
            try {
                access.toggleMode(admin)
                assertEquals(GameMode.CREATIVE, admin.gameMode)
                admin.teleport(Pos(100.0, 140.0, 100.0)).join()
                assertEquals(GameMode.ADVENTURE, admin.gameMode)
            } finally {
                MinecraftServer.getGlobalEventHandler().removeChild(node)
                access.close()
            }
        }
    }

    @Test
    fun `creative test mode is restricted to authorized platform visitors and always restored`() {
        MinecraftServer.init()
        RendererFixture().use { fixture ->
            val admin = fixture.player(Pos(-7.0, 173.0, 17.0))
            val other = fixture.player(Pos(-7.0, 173.0, 17.0))
            admin.gameMode = GameMode.ADVENTURE
            other.gameMode = GameMode.ADVENTURE
            val access = StageNpcDemoAccess(fixture.instance) { it === admin }
            assertFalse(access.toggleMode(other))
            assertEquals(GameMode.ADVENTURE, other.gameMode)
            assertTrue(access.toggleMode(admin))
            assertEquals(GameMode.CREATIVE, admin.gameMode)
            access.moved(admin, Pos(-7.0, 178.0, 16.0))
            assertEquals(GameMode.CREATIVE, admin.gameMode)
            access.moved(admin, Pos(100.0, 140.0, 100.0))
            assertEquals(GameMode.ADVENTURE, admin.gameMode)
            admin.teleport(Pos(100.0, 140.0, 100.0)).join()
            assertFalse(access.toggleMode(admin))
            admin.teleport(Pos(-7.0, 173.0, 17.0)).join()
            access.toggleMode(admin)
            access.restore(admin)
            assertEquals(GameMode.ADVENTURE, admin.gameMode)
            access.toggleMode(admin)
            access.close()
            assertEquals(GameMode.ADVENTURE, admin.gameMode)
        }
    }

    @Test
    fun `demo configuration cannot activate outside stage`() {
        assertFalse(stageNpcDemoEnabled(emptyMap()))
        assertFalse(stageNpcDemoEnabled(mapOf("GROUNDS_PERMISSION_ENVIRONMENT" to "stage")))
        assertTrue(
            stageNpcDemoEnabled(
                mapOf(
                    "GROUNDS_PERMISSION_ENVIRONMENT" to "stage",
                    "GROUNDS_NPC_DEMO_ENABLED" to "true",
                )
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            stageNpcDemoEnabled(
                mapOf(
                    "GROUNDS_PERMISSION_ENVIRONMENT" to "prod",
                    "GROUNDS_NPC_DEMO_ENABLED" to "true",
                )
            )
        }
    }

    @Test
    fun `stage identity permits an isolated map pin without accepting production identity`() {
        assertTrue(
            stageNpcDemoEnabled(
                mapOf(
                    "GROUNDS_PERMISSION_ENVIRONMENT" to "stage",
                    "MAPS_ENVIRONMENT" to "stage-npc-poc",
                    "GROUNDS_NPC_DEMO_ENABLED" to "true",
                )
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            stageNpcDemoEnabled(
                mapOf(
                    "GROUNDS_PERMISSION_ENVIRONMENT" to "prod",
                    "MAPS_ENVIRONMENT" to "stage",
                    "GROUNDS_NPC_DEMO_ENABLED" to "true",
                )
            )
        }
    }
}
