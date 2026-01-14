package gg.grounds.minestom.lobby

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int

class StartLobbyCommand : CliktCommand() {

    val address: String by option("--address").help("The address without a port").default("0.0.0.0")
    val port: Int by option("--port").help("The port").int().default(30066)
    val lobbyAuth: LobbyAuthType by option("--auth")
        .help("Defines which authentication is used").enum<LobbyAuthType>()
        .default(LobbyAuthType.VELOCITY)

    override fun run() {
        LobbyServer.start(address, port, lobbyAuth)
    }
}

fun main(args: Array<String>) = StartLobbyCommand().main(args)