package gg.grounds.minestom.lobby

import gg.grounds.runtime.ServerType
import gg.grounds.runtime.core.GroundsServer
import gg.grounds.runtime.core.MapBlockRenderingModule
import gg.grounds.runtime.core.RuntimeConfig
import net.minestom.server.ServerFlag

object LobbyServer {

    fun start() {
        buildLobbyServer().start()
    }
}

internal fun buildLobbyServer(env: Map<String, String> = System.getenv()): GroundsServer {
    // This must precede configuration/provider discovery: either may initialize ServerFlag.
    // Grounds owns the single JVM shutdown sequence: scene, providers, then Minestom/ticks.
    System.setProperty("minestom.shutdown-on-signal", "false")
    check(!ServerFlag.SHUTDOWN_ON_SIGNAL) {
        "Lobby bootstrap must run before Minestom ServerFlag initializes signal shutdown"
    }
    val runtimeConfig = lobbyRuntimeConfig(env)
    lateinit var server: GroundsServer

    val builder =
        GroundsServer.builder()
            .config(runtimeConfig)
            .discoverProviders()
            .use(MapBlockRenderingModule())
            .use(LobbyModule(fatalStop = { server.stop() }))

    selectedRuntimeProviderIds(env).forEach { providerId -> builder.useProvider(providerId) }

    server = builder.build()
    return server
}

internal fun lobbyRuntimeConfig(env: Map<String, String> = System.getenv()): RuntimeConfig =
    RuntimeConfig.fromEnvironment(env).copy(serverType = ServerType.LOBBY)

internal fun selectedRuntimeProviderIds(env: Map<String, String> = System.getenv()): List<String> =
    buildList {
        val permissionsConfigured = hasPermissionsRuntime(env)
        val notificationsConfigured = hasNotificationsRuntime(env)
        check(!notificationsConfigured || permissionsConfigured) {
            "Notifications runtime requires the permissions runtime"
        }
        // Unconditional: the navigator talks to the proxy over the player's own connection, so
        // there is no service to be configured and nothing to degrade to. Every other entry here
        // is gated because it would fail without its backend; this one would only be missing.
        add("grounds.lobby.navigator")
        // Unconditional for the same reason, and it was missing entirely: the dependency was
        // declared and the provider was discovered, but never selected, so Minestom's own
        // `<name> message` was what players actually saw and nothing ever reached the proxy.
        // Like the navigator it needs no backend — with CHAT_GLOBAL_ENABLED unset it broadcasts
        // inside this lobby instead of failing.
        add("grounds.chat")
        if (hasAgonesSidecar(env)) {
            add("grounds.agones")
        }
        if (permissionsConfigured) {
            add("grounds.permissions")
        }
        if (notificationsConfigured) {
            add("grounds.notifications")
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

private fun hasNotificationsRuntime(env: Map<String, String>): Boolean {
    val configurationKeys =
        listOf("NOTIFICATIONS_SERVICE_URL", "NOTIFICATIONS_SERVER_ID", "PORTAL_BASE_URL")
    val configuredKeys = configurationKeys.filter { !env[it].isNullOrBlank() }
    check(configuredKeys.isEmpty() || configuredKeys.size == configurationKeys.size) {
        "${configurationKeys.joinToString()} must be configured together"
    }
    val channelToken = env["NOTIFICATIONS_CHANNEL_TOKEN"]?.takeIf { it.isNotBlank() }
    check(channelToken == null || configuredKeys.size == configurationKeys.size) {
        "NOTIFICATIONS_CHANNEL_TOKEN requires ${configurationKeys.joinToString()}"
    }
    return channelToken != null && configuredKeys.size == configurationKeys.size
}
