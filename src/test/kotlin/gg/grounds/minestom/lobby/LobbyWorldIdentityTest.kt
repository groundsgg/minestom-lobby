package gg.grounds.minestom.lobby

import gg.grounds.runtime.core.world.WorldIdentity
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import net.kyori.adventure.key.Key
import net.minestom.server.MinecraftServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The lobby is sent to clients under its own world identity, not as `minecraft:overworld` with seed
 * 0, so LOD mods do not mix it with a game map.
 */
class LobbyWorldIdentityTest {

    @Test
    fun `a published map is keyed by its address and version`() {
        val identity = worldIdentity(LobbyMapSource.Published("lobby/mainlobby", 42, SHA))

        assertEquals(Key.key("grounds", "lobby_mainlobby_v42"), identity.key)
        assertEquals(WorldIdentity.of("lobby/mainlobby", 42), identity)
    }

    @Test
    fun `a new version of the map gets a new identity`() {
        val old = worldIdentity(LobbyMapSource.Published("lobby/mainlobby", 41, SHA))
        val new = worldIdentity(LobbyMapSource.Published("lobby/mainlobby", 42, SHA))

        assertNotEquals(old.key, new.key)
        assertNotEquals(old.hashedSeed, new.hashedSeed)
    }

    @Test
    fun `the baked-in world has a fixed identity of its own`(@TempDir tmp: Path) {
        assertEquals(
            Key.key("grounds", "lobby_local"),
            worldIdentity(LobbyMapSource.Local(tmp)).key,
        )
    }

    @Test
    fun `the opened instance carries the identity`(@TempDir tmp: Path) {
        val root = tmp.resolve("bundle")
        root.resolve("region").createDirectories()
        root.resolve("map.json").writeText("""{"spawns":[{"x":1.5,"y":64,"z":-2.5}]}""")
        val source = LobbyMapSource.Published("lobby/mainlobby", 42, SHA)

        val map = LobbyWorld.open(LoadedLobbyMap(root, source))

        val identity = worldIdentity(source)
        assertEquals(identity.key, map.instance.dimensionName)
        assertEquals(identity.hashedSeed, map.instance.hashedSeed)
        assertTrue(MinecraftServer.getInstanceManager().instances.contains(map.instance))
        MinecraftServer.getInstanceManager().unregisterInstance(map.instance)
    }

    companion object {
        private val SHA = "a".repeat(64)

        @JvmStatic
        @BeforeAll
        fun bootMinestom() {
            MinecraftServer.init()
        }
    }
}
