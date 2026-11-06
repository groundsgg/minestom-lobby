package gg.grounds.minestom.lobby

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NpcAddonBindingTest {
    private val sha1 = "1234567890123456789012345678901234567890"

    @Test
    fun `accepts only the addon bound into this distribution`() {
        NpcAddonBinding(sha1).requireMatches(sha1)
        assertThrows(IllegalArgumentException::class.java) {
            NpcAddonBinding(sha1).requireMatches("0".repeat(40))
        }
    }

    @Test
    fun `rejects missing or malformed build bindings`() {
        for (value in listOf("", "abc", "g".repeat(40), "0".repeat(41))) {
            assertThrows(IllegalArgumentException::class.java) { NpcAddonBinding(value) }
        }
    }
}
