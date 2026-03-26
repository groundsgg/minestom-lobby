package gg.grounds.minestom.lobby.commands

import gg.grounds.minestom.lobby.AnvilMapManager
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.arguments.ArgumentType

class LoadWorldCommand(
    private val mapManager: AnvilMapManager,
) : Command("loadWorld") {

    init {
        val nameArgument = ArgumentType.String("name")

        addSyntax({ sender, context ->
            val name = context[nameArgument]

            val result = mapManager.loadMap(name)

            if (result.isFailure) {
                sender.sendMessage("Error: $result")
            } else {
                sender.sendMessage("Reloaded World $name")
            }
        }, nameArgument)
    }
}