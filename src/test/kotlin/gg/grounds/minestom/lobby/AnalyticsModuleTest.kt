package gg.grounds.minestom.lobby

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnalyticsModuleTest {
    @Test
    fun `analytics is on unless switched off`() {
        assertTrue(analyticsEnabled(emptyMap()))
        assertTrue(analyticsEnabled(mapOf("ANALYTICS_ENABLED" to "true")))
        // A blank value is an unset one, not a "no".
        assertTrue(analyticsEnabled(mapOf("ANALYTICS_ENABLED" to "")))
    }

    @Test
    fun `ANALYTICS_ENABLED=false keeps the module out, however it is spelled`() {
        assertFalse(analyticsEnabled(mapOf("ANALYTICS_ENABLED" to "false")))
        assertFalse(analyticsEnabled(mapOf("ANALYTICS_ENABLED" to "FALSE")))
        assertFalse(analyticsEnabled(mapOf("ANALYTICS_ENABLED" to " false ")))
    }

    @Test
    fun `stopping a module that never installed is a no-op`() {
        val module = AnalyticsModule(minigameId = "lobby")

        module.stop()

        assertEquals("grounds.lobby.analytics", module.id)
    }
}
