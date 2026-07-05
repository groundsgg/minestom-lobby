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
    val runtimeConfig = lobbyRuntimeConfig(env)

    val builder =
        GroundsServer.builder().config(runtimeConfig).discoverProviders().use(LobbyModule())

    selectedRuntimeProviderIds(env).forEach { providerId -> builder.useProvider(providerId) }

    return builder.build()
}

internal fun lobbyRuntimeConfig(env: Map<String, String> = System.getenv()): RuntimeConfig =
    RuntimeConfig.fromEnvironment(env).copy(serverType = ServerType.LOBBY)

internal fun selectedRuntimeProviderIds(env: Map<String, String> = System.getenv()): List<String> =
    buildList {
        if (hasAgonesSidecar(env)) {
            add("grounds.agones")
        }
        if (hasPermissionsTarget(env)) {
            add("grounds.permissions")
        }
    }

private fun hasAgonesSidecar(env: Map<String, String>): Boolean =
    !env["AGONES_SDK_HTTP_PORT"].isNullOrBlank() || !env["AGONES_SDK_GRPC_PORT"].isNullOrBlank()

private fun hasPermissionsTarget(env: Map<String, String>): Boolean =
    !env["PERMISSIONS_GRPC_TARGET"].isNullOrBlank()
