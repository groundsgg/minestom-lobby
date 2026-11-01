package gg.grounds.minestom.lobby.scene

import java.util.UUID
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Metadata
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket
import net.minestom.server.network.packet.server.play.SpawnEntityPacket
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeSolidPacketTraceTest {
    @Test
    fun `trace ignores unrelated packets omits UUIDs and respects its hard budget`() {
        MinecraftServer.init()
        RendererFixture().use { fixture ->
            val player = fixture.player()
            val logs = mutableListOf<String>()
            val trace = NativeSolidPacketTrace(fixture.instance, setOf(2), 2, logs::add)
            trace.capture(player, EntityMetaDataPacket(999, mapOf(0 to Metadata.Byte(32))), false)
            assertTrue(logs.isEmpty())
            val uuid = UUID.randomUUID()
            val spawn =
                SpawnEntityPacket(
                    2,
                    uuid,
                    EntityType.SHULKER,
                    Pos(1.25, 173.0, 2.5),
                    0f,
                    0,
                    Vec.ZERO,
                )
            repeat(5) { trace.capture(player, spawn, false) }
            assertEquals(2, logs.size)
            assertTrue(
                logs.all {
                    "minecraft:shulker" in it &&
                        uuid.toString() !in it &&
                        player.uuid.toString() !in it
                }
            )
            trace.close()
        }
    }

    @Test
    fun `closed trace cannot emit another snapshot`() {
        MinecraftServer.init()
        RendererFixture().use { fixture ->
            val player = fixture.player()
            val logs = mutableListOf<String>()
            val trace = NativeSolidPacketTrace(fixture.instance, setOf(2), sink = logs::add)
            trace.close()
            trace.capture(player, EntityMetaDataPacket(2, mapOf(0 to Metadata.Byte(32))), false)
            assertTrue(logs.isEmpty())
        }
    }
}
