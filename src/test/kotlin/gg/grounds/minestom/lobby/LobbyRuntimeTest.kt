package gg.grounds.minestom.lobby

import gg.grounds.runtime.ServerType
import gg.grounds.runtime.core.ProxyMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LobbyRuntimeTest {
    @Test
    fun `map rendering is installed before the lobby world`() {
        val server = buildLobbyServer(emptyMap())
        val field = server.javaClass.getDeclaredField("modules").apply { isAccessible = true }
        val ids =
            (field.get(server) as List<*>).map { module ->
                val id = module!!.javaClass.getDeclaredField("id").apply { isAccessible = true }
                id.get(module) as String
            }
        assertTrue(ids.indexOf("grounds.map-rendering") >= 0)
        assertTrue(ids.indexOf("grounds.map-rendering") < ids.indexOf("grounds.lobby"))
    }

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

    /**
     * Chat was on the classpath and discovered by the SPI for months, but never named here, so
     * players saw Minestom's own `<name> message` and nothing reached the proxy. Being discovered
     * is not being selected — this is the test that would have caught it.
     */
    @Test
    fun `lobby always selects chat, with or without any backend configured`() {
        assertTrue(selectedRuntimeProviderIds(emptyMap()).contains("grounds.chat"))
        assertTrue(
            selectedRuntimeProviderIds(mapOf("AGONES_SDK_HTTP_PORT" to "9358"))
                .contains("grounds.chat")
        )
    }

    @Test
    fun `lobby selects navigator before chat without backend configuration`() {
        assertEquals(
            listOf("grounds.lobby.navigator", "grounds.chat"),
            selectedRuntimeProviderIds(emptyMap()),
        )
    }

    @Test
    fun `lobby selects agones provider only when sidecar is detected`() {
        val standalone = selectedRuntimeProviderIds(emptyMap())
        val withAgones = selectedRuntimeProviderIds(mapOf("AGONES_SDK_HTTP_PORT" to "9358"))

        assertFalse(standalone.contains("grounds.agones"))
        assertTrue(withAgones.contains("grounds.agones"))
    }

    @Test
    fun `lobby selects permissions provider only when REST runtime is fully configured`() {
        val standalone = selectedRuntimeProviderIds(emptyMap())
        val withPermissions =
            selectedRuntimeProviderIds(
                mapOf(
                    "PERMISSIONS_SERVICE_URL" to "http://service-permissions-runtime:8080",
                    "PERMISSIONS_TOKEN_FILE" to "/var/run/secrets/grounds/permissions-token",
                )
            )

        assertFalse(standalone.contains("grounds.permissions"))
        assertTrue(withPermissions.contains("grounds.permissions"))
    }

    @Test
    fun `lobby rejects partial permissions REST runtime configuration`() {
        assertThrows(IllegalStateException::class.java) {
            selectedRuntimeProviderIds(
                mapOf("PERMISSIONS_SERVICE_URL" to "http://service-permissions-runtime:8080")
            )
        }
        assertThrows(IllegalStateException::class.java) {
            selectedRuntimeProviderIds(
                mapOf("PERMISSIONS_TOKEN_FILE" to "/var/run/secrets/grounds/permissions-token")
            )
        }
    }

    @Test
    fun `lobby selects agones and permissions providers together`() {
        val providers =
            selectedRuntimeProviderIds(
                mapOf(
                    "AGONES_SDK_GRPC_PORT" to "9357",
                    "PERMISSIONS_SERVICE_URL" to "http://service-permissions-runtime:8080",
                    "PERMISSIONS_TOKEN_FILE" to "/var/run/secrets/grounds/permissions-token",
                )
            )

        assertEquals(
            listOf(
                "grounds.lobby.navigator",
                "grounds.chat",
                "grounds.agones",
                "grounds.permissions",
            ),
            providers,
        )
    }

    @Test
    fun `lobby always selects the navigator`() {
        assertTrue(selectedRuntimeProviderIds(emptyMap()).contains("grounds.lobby.navigator"))
    }
}
