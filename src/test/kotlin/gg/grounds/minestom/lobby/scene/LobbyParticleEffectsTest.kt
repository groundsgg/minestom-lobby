package gg.grounds.minestom.lobby.scene

import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.scene.format.*
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.network.packet.server.play.ParticlePacket
import net.minestom.server.particle.Particle
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LobbyParticleEffectsTest {
    @Test
    fun `exact catalogued particle emits a real bounded effect and unknown keys stay rejected`() {
        MinecraftServer.init()
        val key = AssetKey("grounds:scene/happy_villager")
        val assets = GroundsAssetCatalog.catalog
        assertEquals(AssetKind.PARTICLE, assets.assets[key]?.kind)
        val effects = LobbySceneEffects(assets)
        assertTrue(effects.supports(key, AssetKind.PARTICLE))
        assertFalse(effects.supports(AssetKey("grounds:scene/unknown"), AssetKind.PARTICLE))
        assertFalse(effects.supports(key, AssetKind.SOUND))
        RendererFixture().use { fixture ->
            val player = fixture.player()
            effects.emitParticle(
                fixture.instance,
                key,
                Pos(1.0, 2.0, 3.0),
                12,
                Vec3(.2, .3, .4),
                .1,
            )
            val packet = player.connection.packets.filterIsInstance<ParticlePacket>().single()
            assertEquals(Particle.HAPPY_VILLAGER, packet.particle())
            assertEquals(12, packet.particleCount())
            assertEquals(1.0, packet.x())
            assertEquals(2.0, packet.y())
            assertEquals(3.0, packet.z())
            assertThrows(IllegalArgumentException::class.java) {
                effects.emitParticle(
                    fixture.instance,
                    AssetKey("grounds:scene/unknown"),
                    Pos.ZERO,
                    1,
                    Vec3(0.0, 0.0, 0.0),
                    0.0,
                )
            }
        }
    }
}
