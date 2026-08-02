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
        // Unconditional: the navigator talks to the proxy over the player's own connection, so
        // there is no service to be configured and nothing to degrade to. Every other entry here
        // is gated because it would fail without its backend; this one would only be missing.
        add("grounds.lobby.navigator")
        if (hasAgonesSidecar(env)) {
            add("grounds.agones")
        }
        if (hasPermissionsRuntime(env)) {
            add("grounds.permissions")
        }
    }

private fun hasAgonesSidecar(env: Map<String, String>): Boolean =
    !env["AGONES_SDK_HTTP_PORT"].isNullOrBlank() || !env["AGONES_SDK_GRPC_PORT"].isNullOrBlank()

private fun hasPermissionsRuntime(env: Map<String, String>): Boolean {
    val serviceUrl = env["PERMISSIONS_SERVICE_URL"]?.takeIf { it.isNotBlank() }
    val tokenFile = env["PERMISSIONS_TOKEN_FILE"]?.takeIf { it.isNotBlank() }
    check((serviceUrl == null) == (tokenFile == null)) {
        "PERMISSIONS_SERVICE_URL and PERMISSIONS_TOKEN_FILE must be configured together"
    }
    return serviceUrl != null
}
