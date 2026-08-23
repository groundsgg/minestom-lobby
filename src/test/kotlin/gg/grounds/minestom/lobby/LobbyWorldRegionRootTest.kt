package gg.grounds.minestom.lobby

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The invariant AnvilLoader actually has: it is handed the directory that *contains* `region/`, and
 * it resolves `region` itself.
 *
 * This is the check that was missing when the dimension-aware constructor was introduced. That one
 * resolves `dimensions/<namespace>/<value>/region`, which this resolution has already stripped —
 * together they look for a directory no layout has, and the lobby renders as empty air.
 */
class LobbyWorldRegionRootTest {

    @Test
    fun `a registry bundle keeps region at the top`(@TempDir tmp: Path) {
        val bundle = tmp.resolve("bundle")
        bundle.resolve("region").createDirectories()
        bundle.resolve("region").resolve("r.0.0.mca").createFile()

        assertEquals(bundle, regionRoot(bundle))
        assertTrue(Files.isDirectory(regionRoot(bundle).resolve("region")))
    }

    @Test
    fun `an exported world keeps region one dimension deep`(@TempDir tmp: Path) {
        val world = tmp.resolve("lobby")
        val overworld = world.resolve("dimensions/minecraft/overworld")
        overworld.resolve("region").createDirectories()
        overworld.resolve("region").resolve("r.0.0.mca").createFile()

        assertEquals(overworld, regionRoot(world))
        assertTrue(Files.isDirectory(regionRoot(world).resolve("region")))
    }

    @Test
    fun `whatever the layout, the answer contains region`(@TempDir tmp: Path) {
        // The one sentence both cases have to satisfy, and the one the loader depends on.
        for (layout in listOf("region", "dimensions/minecraft/overworld/region")) {
            val root = tmp.resolve(layout.replace('/', '-'))
            root.resolve(layout).createDirectories()
            assertTrue(Files.isDirectory(regionRoot(root).resolve("region")), layout)
        }
    }
}
