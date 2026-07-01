package gg.grounds.minestom.lobby

import gg.grounds.runtime.ServerType
import gg.grounds.runtime.core.GroundsServer
import gg.grounds.runtime.core.ProxyMode
import gg.grounds.runtime.core.RuntimeConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LobbyRuntimeTest {
    @Test
    fun `lobby server uses runtime config for lobby server type and bind address`() {
        val server =
            buildLobbyServer(
                env = mapOf("GROUNDS_BIND_HOST" to "127.0.0.1", "GROUNDS_BIND_PORT" to "25577")
            )

        val config = server.runtimeConfig()

        assertEquals(ServerType.LOBBY, config.serverType)
        assertEquals("127.0.0.1", config.host)
        assertEquals(25577, config.port)
        assertEquals("Grounds Lobby", config.serverBrand)
        assertEquals(listOf("grounds.lobby"), server.installedModuleIds())
    }

    @Test
    fun `lobby server delegates velocity auth config to runtime`() {
        val server =
            buildLobbyServer(
                env =
                    mapOf(
                        "GROUNDS_PROXY_MODE" to "velocity",
                        "GROUNDS_VELOCITY_FORWARDING_SECRET" to "secret",
                    )
            )

        val config = server.runtimeConfig()

        assertEquals(ProxyMode.VELOCITY, config.proxy.mode)
        assertEquals("secret", config.proxy.velocityForwardingSecret)
    }

    @Test
    fun `lobby server installs agones provider only when sidecar is detected`() {
        val standalone = buildLobbyServer(env = emptyMap())
        val withAgones = buildLobbyServer(env = mapOf("AGONES_SDK_HTTP_PORT" to "9358"))

        assertFalse(standalone.installedModuleIds().contains("grounds.agones"))
        assertTrue(withAgones.installedModuleIds().contains("grounds.agones"))
    }
}

private fun GroundsServer.runtimeConfig(): RuntimeConfig {
    val field = GroundsServer::class.java.getDeclaredField("config")
    field.isAccessible = true
    return field.get(this) as RuntimeConfig
}

private fun GroundsServer.installedModuleIds(): List<String> {
    val modulesField = GroundsServer::class.java.getDeclaredField("modules")
    modulesField.isAccessible = true
    val modules = modulesField.get(this) as List<*>

    return modules.map { installed ->
        val idField = checkNotNull(installed).javaClass.getDeclaredField("id")
        idField.isAccessible = true
        idField.get(installed) as String
    }
}
