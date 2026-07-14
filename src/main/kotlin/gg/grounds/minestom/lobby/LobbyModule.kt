package gg.grounds.minestom.lobby

import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsServerContext
import net.minestom.server.MinecraftServer
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import org.slf4j.LoggerFactory

internal class LobbyModule : GroundsModule {
    private val logger = LoggerFactory.getLogger(LobbyModule::class.java)
    private var eventNode: EventNode<Event>? = null

    override val id: String = "grounds.lobby"

    override fun install(ctx: GroundsServerContext) {
        val (instanceContainer, spawn) = LobbyWorld.createInstance()
        val node = ctx.eventNode("grounds-lobby")
        LobbyEvents.register(node, instanceContainer, spawn)
        MinecraftServer.getGlobalEventHandler().addChild(node)
        eventNode = node

        logger.info(
            "Installed lobby module successfully (serverType={}, environment={})",
            ctx.serverType,
            ctx.environment,
        )
    }

    override fun stop() {
        eventNode?.let(MinecraftServer.getGlobalEventHandler()::removeChild)
        eventNode = null
    }
}
