package gg.grounds.minestom.lobby

import net.minestom.server.MinecraftServer
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.LightingChunk
import net.minestom.server.instance.block.Block
import net.minestom.server.utils.chunk.ChunkSupplier

private const val SUNRISE_TIME: Long = 6000

internal object LobbyWorld {
    fun createInstance(): InstanceContainer {
        val instanceContainer = MinecraftServer.getInstanceManager().createInstanceContainer()

        instanceContainer.setGenerator { it.modifier().fillHeight(0, 40, Block.GRASS_BLOCK) }
        instanceContainer.chunkSupplier = ChunkSupplier { instance, x, z ->
            LightingChunk(instance, x, z)
        }

        val clock = instanceContainer.defaultClock()!!
        clock.rate(0f)
        clock.time(SUNRISE_TIME)

        return instanceContainer
    }
}
