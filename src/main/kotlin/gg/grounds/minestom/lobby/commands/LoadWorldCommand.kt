package gg.grounds.minestom.lobby.commands

import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
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

            mapManager.loadMap(name)
                .onOk { sender.sendMessage("Reloaded World $name") }
                .onErr { error -> sender.sendMessage("Failed to load World $name, ${error.path} does not exist") }
        }, nameArgument)
    }
}