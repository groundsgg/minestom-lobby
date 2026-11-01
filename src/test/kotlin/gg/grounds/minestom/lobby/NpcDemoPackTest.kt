package gg.grounds.minestom.lobby

import gg.grounds.minestom.lobby.scene.RendererFixture
import net.kyori.adventure.resource.ResourcePackStatus
import net.minestom.server.MinecraftServer
import net.minestom.server.network.packet.server.common.ResourcePackPopPacket
import net.minestom.server.network.packet.server.common.ResourcePackPushPacket
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NpcDemoPackTest {
    @Test
    fun `addon is additive and configuration waits for the exact pack acknowledgement`() {
        MinecraftServer.init()
        val pack =
            NpcDemoPack(
                "https://cdn.grounds.gg/npc-test.zip",
                "1234567890123456789012345678901234567890",
            )
        assertFalse(pack.request.replace())
        assertTrue(pack.request.required())
        RendererFixture().use { fixture ->
            val player = fixture.player()
            player.connection.packets.clear()
            pack.offer(player)
            assertTrue(
                player.connection.packets.filterIsInstance<ResourcePackPopPacket>().isEmpty()
            )
            val offered =
                player.connection.packets.filterIsInstance<ResourcePackPushPacket>().single()
            assertEquals(pack.id, offered.id())
            val future = player.resourcePackFuture!!
            assertFalse(future.isDone)
            player.onResourcePackStatus(pack.id, ResourcePackStatus.SUCCESSFULLY_LOADED)
            assertTrue(future.isDone)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NpcDemoPack("http://example.com/test.zip", "bad")
        }
    }
}
