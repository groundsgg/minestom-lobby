package gg.grounds.minestom.lobby

import java.net.SocketAddress
import java.util.UUID
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class LobbyEventsTest {
    @Test
    fun `configuration assigns the supplied instance and exact spawn point`() {
        val node = EventNode.all("lobby-events-test")
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val spawn = Pos(10.5, 64.0, -3.5, 90.0f, -12.5f)
        val player = Player(FakeConnection(), GameProfile(UUID.randomUUID(), "Alex"))
        val event = AsyncPlayerConfigurationEvent(player, true)
        LobbyEvents.register(node, instance, spawn)

        Thread.startVirtualThread { node.call(event) }.join()

        assertSame(instance, event.spawningInstance)
        assertEquals(spawn, player.respawnPoint)
    }

    private class FakeConnection : PlayerConnection() {
        override fun sendPacket(packet: SendablePacket) {}

        override fun getRemoteAddress(): SocketAddress? = null
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun bootMinestom() {
            MinecraftServer.init()
        }
    }
}
