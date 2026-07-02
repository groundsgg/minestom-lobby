package gg.grounds.minestom.lobby

import gg.grounds.runtime.ServerType
import gg.grounds.runtime.core.ProxyMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LobbyRuntimeTest {
    @Test
    fun `lobby runtime config delegates bind address and brand to runtime env`() {
        val config =
            lobbyRuntimeConfig(
                mapOf(
                    "GROUNDS_BIND_HOST" to "127.0.0.1",
                    "GROUNDS_BIND_PORT" to "25577",
                    "GROUNDS_SERVER_BRAND" to "Grounds Lobby",
                )
            )

        assertEquals(ServerType.LOBBY, config.serverType)
        assertEquals("127.0.0.1", config.host)
        assertEquals(25577, config.port)
        assertEquals("Grounds Lobby", config.serverBrand)
    }

    @Test
    fun `lobby runtime config delegates velocity auth config to runtime`() {
        val config =
            lobbyRuntimeConfig(
                mapOf(
                    "GROUNDS_PROXY_MODE" to "velocity",
                    "GROUNDS_VELOCITY_FORWARDING_SECRET" to "secret",
                )
            )

        assertEquals(ProxyMode.VELOCITY, config.proxy.mode)
        assertEquals("secret", config.proxy.velocityForwardingSecret)
    }

    @Test
    fun `lobby selects agones provider only when sidecar is detected`() {
        val standalone = selectedRuntimeProviderIds(emptyMap())
        val withAgones = selectedRuntimeProviderIds(mapOf("AGONES_SDK_HTTP_PORT" to "9358"))

        assertFalse(standalone.contains("grounds.agones"))
        assertTrue(withAgones.contains("grounds.agones"))
    }
}
