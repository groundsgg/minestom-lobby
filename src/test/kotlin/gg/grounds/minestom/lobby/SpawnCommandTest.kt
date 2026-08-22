package gg.grounds.minestom.lobby

import java.net.SocketAddress
import java.util.UUID
import net.minestom.server.MinecraftServer
import net.minestom.server.command.CommandManager
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class SpawnCommandTest {
    @Test
    fun `teleports a player to the configured spawn`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val player = Player(FakeConnection(), GameProfile(UUID.randomUUID(), "Alex"))
        player.setInstance(instance, Pos(0.0, 64.0, 0.0)).join()
        val commandManager = CommandManager()
        commandManager.register(SpawnCommand(Pos(10.5, 64.0, -3.5, 90.0f, -12.5f)))

        commandManager.execute(player, "spawn")

        assertEquals(10.5, player.position.x())
        assertEquals(64.0, player.position.y())
        assertEquals(-3.5, player.position.z())
        assertEquals(90.0f, player.position.yaw())
        assertEquals(-12.5f, player.position.pitch())
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
