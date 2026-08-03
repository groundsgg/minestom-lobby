package gg.grounds.minestom.lobby

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LobbyPointsTest {

    @TempDir lateinit var world: Path

    /**
     * The builder stood where players should land and marked it. That has to survive the trip
     * through the bundle, facing included — a spawn that drops players into a wall is a bug report
     * nobody can explain from coordinates alone.
     */
    @Test
    fun `reads the spawn a builder marked`() {
        world.resolve("grounds").createDirectories()
        world
            .resolve("grounds/pois.json")
            .writeText(
                """{"format":1,"pois":{"spawn":{"x":10.5,"y":64.0,"z":-3.5,"yaw":90.0,"pitch":-12.5}}}"""
            )

        val spawn = LobbyPoints.read(world, "spawn")

        assertEquals(10.5, spawn!!.x())
        assertEquals(64.0, spawn.y())
        assertEquals(-3.5, spawn.z())
        assertEquals(90.0f, spawn.yaw())
        assertEquals(-12.5f, spawn.pitch())
    }

    /** A world published before points existed simply has none; the caller falls back. */
    @Test
    fun `a map without points reads as null`() {
        assertNull(LobbyPoints.read(world, "spawn"))
    }

    /** A broken file must not stop a lobby from starting — an old world beats no world. */
    @Test
    fun `a corrupt file reads as null rather than throwing`() {
        world.resolve("grounds").createDirectories()
        world.resolve("grounds/pois.json").writeText("{ not json")

        assertNull(LobbyPoints.read(world, "spawn"))
    }

    @Test
    fun `a point the map does not carry reads as null`() {
        world.resolve("grounds").createDirectories()
        world.resolve("grounds/pois.json").writeText("""{"format":1,"pois":{"other":{"x":0,"y":0,"z":0,"yaw":0,"pitch":0}}}""")

        assertNull(LobbyPoints.read(world, "spawn"))
        Files.deleteIfExists(world.resolve("grounds/pois.json"))
    }
}
