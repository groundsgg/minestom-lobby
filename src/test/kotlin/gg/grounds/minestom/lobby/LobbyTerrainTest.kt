package gg.grounds.minestom.lobby

import gg.grounds.runtime.core.world.GroundsWorld
import gg.grounds.runtime.core.world.WorldIdentity
import java.nio.file.Path
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.block.Block
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LobbyTerrainTest {
    @TempDir lateinit var root: Path

    @Test
    fun `prepares spawn and distant NPC surroundings before their first packet`() {
        val instance = GroundsWorld.open(root, WorldIdentity.of("test/terrain"))
        val map = LobbyMap(instance, Pos.ZERO, LoadedLobbyMap(root, LobbyMapSource.Local(root)))
        try {
            instance.loadChunk(0, 0).join()
            instance.setBlock(15, 64, 0, Block.GLOWSTONE)
            val centres = listOf(Pos(-32.0, 64.0, -16.0), Pos(64.0, 64.0, 0.0))
            LobbyTerrain.prepare(map, centres, radius = 1)

            assertNotNull(instance.getChunk(-3, -2), "Negative-coordinate NPC halo is missing")
            assertNotNull(instance.getChunk(5, 1), "Distant NPC halo is missing")
            assertNotNull(instance.getChunk(0, 1), "Spawn surroundings are missing")
            assertEquals(25, instance.chunks.size, "Overlapping chunks must only load once")
            assertTrue(
                instance.chunks.none(::dirty),
                "Loading neighbours after lighting dirties them again",
            )
            assertEquals(
                15,
                instance.getChunk(0, 0)!!.getSection(4).blockLight().getLevel(15, 0, 0),
            )
            assertEquals(14, instance.getChunk(1, 0)!!.getSection(4).blockLight().getLevel(0, 0, 0))

            instance.setBlock(15, 64, 0, Block.AIR)
            assertTrue(
                dirty(instance.getChunk(0, 0)!!),
                "Normal block updates must still invalidate lighting",
            )
        } finally {
            MinecraftServer.getInstanceManager().unregisterInstance(instance)
        }
    }

    @Test
    fun `interruption cannot detach chunk loads from terrain preparation`() {
        val instance = GroundsWorld.open(root, WorldIdentity.of("test/terrain-interrupted"))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        instance.setChunkLoader(
            object : net.minestom.server.instance.ChunkLoader {
                override fun supportsParallelLoading() = true

                override fun loadChunk(
                    owner: net.minestom.server.instance.Instance,
                    x: Int,
                    z: Int,
                ): Chunk {
                    entered.countDown()
                    release.await()
                    return owner.chunkSupplier.createChunk(owner, x, z)
                }

                override fun saveChunk(chunk: Chunk) = Unit
            }
        )
        val map = LobbyMap(instance, Pos.ZERO, LoadedLobbyMap(root, LobbyMapSource.Local(root)))
        val worker =
            Thread.ofPlatform().start {
                try {
                    LobbyTerrain.prepare(map, emptyList(), radius = 1)
                } finally {
                    finished.countDown()
                }
            }
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            worker.interrupt()
            assertFalse(
                finished.await(100, java.util.concurrent.TimeUnit.MILLISECONDS),
                "Returning early lets installation unregister a world with owned loads still running",
            )
            release.countDown()
            worker.join(5000)
            assertFalse(worker.isAlive)
            assertEquals(9, instance.chunks.size)
        } finally {
            release.countDown()
            worker.join(5000)
            MinecraftServer.getInstanceManager().unregisterInstance(instance)
        }
    }

    private fun dirty(chunk: Chunk) =
        chunk.sections.any { it.skyLight().requiresUpdate() || it.blockLight().requiresUpdate() }

    companion object {
        @JvmStatic
        @BeforeAll
        fun boot() {
            MinecraftServer.init()
        }
    }
}
