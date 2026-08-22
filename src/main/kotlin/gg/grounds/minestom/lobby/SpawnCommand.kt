package gg.grounds.minestom.lobby

import net.kyori.adventure.text.Component
import net.minestom.server.command.builder.Command
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player

internal class SpawnCommand(private val spawn: Pos) : Command("spawn") {
    init {
        setDefaultExecutor { sender, _ ->
            if (sender is Player) {
                sender.teleport(spawn)
            } else {
                sender.sendMessage(Component.text("This command is player-only."))
            }
        }
    }
}
