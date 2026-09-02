package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.LocalBounds
import gg.grounds.scene.format.Vec3
import gg.grounds.scene.minestom.SceneViewerVisualState
import java.net.InetSocketAddress
import java.util.UUID
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class PlaceholderDisplayHandleTest {
    @Test
    fun `highlight packet is private and reapplied after tracking`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val a = testPlayer(instance)
        val b = testPlayer(instance)
        val display = HighlightableBlockDisplay().also { it.setInstance(instance).join() }
        val handle =
            PlaceholderDisplayHandle(display, LocalBounds(Vec3(.5, .5, .5), Vec3(1.0, 1.0, 1.0)))
        display.addViewer(a)
        display.addViewer(b)
        a.connection.packets.clear()
        b.connection.packets.clear()
        display.entityMeta.setOnFire(true)

        handle.applyViewerState(a, SceneViewerVisualState(highlighted = true))

        assertTrue(a.connection.metaFlags(display.entityId).last() and 0x41 == 0x41)
        assertFalse(b.connection.metaFlags(display.entityId).any { it and 0x40 != 0 })
        assertFalse(display.entityMeta.isHasGlowingEffect)
        a.connection.packets.clear()
        display.removeViewer(a)
        display.addViewer(a)
        assertTrue(a.connection.metaFlags(display.entityId).last() and 0x41 == 0x41)

        handle.clearViewerState(a)
        assertFalse(a.connection.metaFlags(display.entityId).last() and 0x40 != 0)
        a.connection.packets.clear()
        display.removeViewer(a)
        display.addViewer(a)
        assertFalse(a.connection.metaFlags(display.entityId).last() and 0x40 != 0)
        a.connection.packets.clear()
        handle.close()
        assertTrue(display.viewerState == null)
        assertTrue(display.isRemoved)
    }

    private fun testPlayer(instance: net.minestom.server.instance.Instance) =
        TestPlayer().also { it.setInstance(instance, Pos.ZERO).join() }

    private class TestPlayer : Player(Connection(), GameProfile(UUID.randomUUID(), "test")) {
        val connection
            get() = super.getPlayerConnection() as Connection
    }

    private class Connection : PlayerConnection() {
        val packets = mutableListOf<SendablePacket>()

        override fun sendPacket(packet: SendablePacket) {
            packets += packet
        }

        override fun getRemoteAddress() = InetSocketAddress(0)

        fun metaFlags(id: Int) =
            packets
                .filterIsInstance<EntityMetaDataPacket>()
                .filter { it.entityId() == id }
                .mapNotNull { it.entries()[0]?.value() as? Byte }
                .map { it.toInt() }
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun boot() {
            MinecraftServer.init()
        }
    }
}
