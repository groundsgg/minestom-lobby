package gg.grounds.minestom.lobby

import gg.grounds.modules.ServiceRegistry
import gg.grounds.runtime.GroundsServerContext
import gg.grounds.runtime.RuntimeEnvironment
import gg.grounds.runtime.ServerType
import net.minestom.server.MinecraftServer
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class LobbyModuleLifecycleTest {
    @Test
    fun `install registers spawn command and stop unregisters it`() {
        val commandManager = MinecraftServer.getCommandManager()
        val module = LobbyModule()
        assertNull(commandManager.getCommand("spawn"))

        try {
            module.install(TestContext)

            assertNotNull(commandManager.getCommand("spawn"))

            module.stop()

            assertNull(commandManager.getCommand("spawn"))
        } finally {
            module.stop()
        }
    }

    private object TestContext : GroundsServerContext {
        override val serverType: ServerType = ServerType.LOBBY
        override val environment: RuntimeEnvironment = RuntimeEnvironment.TEST
        override val services: ServiceRegistry
            get() = error("LobbyModule does not use services")

        override fun eventNode(name: String): EventNode<Event> = EventNode.all(name)

        override fun onShutdown(action: () -> Unit) = Unit
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun bootMinestom() {
            MinecraftServer.init()
        }
    }
}
