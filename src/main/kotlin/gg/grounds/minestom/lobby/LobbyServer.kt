package gg.grounds.minestom.lobby

import gg.grounds.runtime.ServerType
import gg.grounds.runtime.core.GroundsServer
import gg.grounds.runtime.core.RuntimeConfig

object LobbyServer {

    fun start() {
        buildLobbyServer().start()
    }
}

internal fun buildLobbyServer(env: Map<String, String> = System.getenv()): GroundsServer {
    val runtimeConfig =
        RuntimeConfig.fromEnvironment(env)
            .copy(serverType = ServerType.LOBBY, serverBrand = "Grounds Lobby")

    val builder =
        GroundsServer.builder().config(runtimeConfig).discoverProviders().use(LobbyModule())

    if (hasAgonesSidecar(env)) {
        builder.useProvider("grounds.agones")
    }

    return builder.build()
}

internal fun hasAgonesSidecar(env: Map<String, String>): Boolean =
    !env["AGONES_SDK_HTTP_PORT"].isNullOrBlank() || !env["AGONES_SDK_GRPC_PORT"].isNullOrBlank()
