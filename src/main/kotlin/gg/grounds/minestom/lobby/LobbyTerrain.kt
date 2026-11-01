package gg.grounds.minestom.lobby

import gg.grounds.runtime.core.world.GroundsWorld
import java.util.concurrent.CompletableFuture
import net.minestom.server.ServerFlag
import net.minestom.server.coordinate.Point
import net.minestom.server.thread.MinestomThread

/** Prepare visitor areas before ticking/admission, without loading the entire authored map. */
internal object LobbyTerrain {
    fun prepare(
        world: LobbyMap,
        centres: List<Point>,
        radius: Int = ServerFlag.CHUNK_VIEW_DISTANCE + 1,
    ) {
        require(radius >= 0)
        check(Thread.currentThread() !is MinestomThread) {
            "Terrain preparation must precede ticking"
        }
        val instance = world.instance
        val areas = (listOf(world.spawn) + centres).distinctBy { it.chunkX() to it.chunkZ() }
        val chunks =
            areas
                .flatMap { centre ->
                    (centre.chunkX() - radius..centre.chunkX() + radius).flatMap { x ->
                        (centre.chunkZ() - radius..centre.chunkZ() + radius).map { z -> x to z }
                    }
                }
                .toSet()
        // A newly loaded chunk invalidates its neighbours. Finish the entire union first;
        // lighting one area before loading the next would dirty the overlap again.
        // Keep ownership until all loads finish, including interruption/failure. A timed or
        // interruptible wait could unregister the instance before late loads cache new chunks.
        CompletableFuture.allOf(*chunks.map { (x, z) -> instance.loadChunk(x, z) }.toTypedArray())
            .join()
        areas.forEach { centre -> GroundsWorld.preload(instance, centre, radius).join() }
    }
}
