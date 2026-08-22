package gg.grounds.minestom.lobby

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LobbySpawnTest {

    @TempDir lateinit var world: Path

    /** A published point belongs to its version and must override the export-time sidecar. */
    @Test
    fun `prefers the versioned spawn point over the map sidecar`() {
        world.resolve("grounds").createDirectories()
        world
            .resolve("grounds/pois.json")
            .writeText(
                """{"format":1,"pois":{"spawn":{"x":10.5,"y":64.0,"z":-3.5,"yaw":90.0,"pitch":-12.5}}}"""
            )
        world
            .resolve("map.json")
            .writeText(
                """{"spawns":[{"x":14.5,"y":3.0,"z":-1.5,"yaw":180.0,"pitch":0.0}]}"""
            )

        val spawn = LobbySpawn.resolve(world)

        assertEquals(10.5, spawn.x())
        assertEquals(64.0, spawn.y())
        assertEquals(-3.5, spawn.z())
        assertEquals(90.0f, spawn.yaw())
        assertEquals(-12.5f, spawn.pitch())
    }

    /** Exported maps from before versioned points remain playable at their authored spawn. */
    @Test
    fun `falls back to the map sidecar including facing`() {
        world
            .resolve("map.json")
            .writeText(
                """{"spawns":[{"x":14.5,"y":3.0,"z":-1.5,"yaw":180.0,"pitch":0.0}]}"""
            )

        val spawn = LobbySpawn.resolve(world)

        assertEquals(14.5, spawn.x())
        assertEquals(3.0, spawn.y())
        assertEquals(-1.5, spawn.z())
        assertEquals(180.0f, spawn.yaw())
        assertEquals(0.0f, spawn.pitch())
    }
}
