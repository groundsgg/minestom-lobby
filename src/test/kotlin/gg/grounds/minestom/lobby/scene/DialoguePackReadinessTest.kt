package gg.grounds.minestom.lobby.scene

import java.nio.ByteBuffer
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DialoguePackReadinessTest {
    @Test
    fun `Bedrock forwarded edition and Floodgate identities cannot enable NPC graphics`() {
        assertFalse(DialoguePackReadiness.allowsGraphics("bedrock", UUID.randomUUID()))
        assertFalse(DialoguePackReadiness.allowsGraphics("BEDROCK", UUID.randomUUID()))
        assertFalse(DialoguePackReadiness.allowsGraphics(null, UUID(0, 42)))
        assertTrue(DialoguePackReadiness.allowsGraphics(null, UUID.randomUUID()))
    }

    @Test
    fun `wire requires current player positive revision exact shape and valid asset pin`() {
        val id = UUID.randomUUID()
        fun payload(revision: Long = 1, flag: Byte = 1, pin: String = "a".repeat(64)) =
            ByteBuffer.allocate(90)
                .put(1)
                .putLong(id.mostSignificantBits)
                .putLong(id.leastSignificantBits)
                .putLong(revision)
                .put(flag)
                .put(pin.toByteArray(Charsets.US_ASCII))
                .array()
        assertEquals(
            DialoguePackReadiness.State(1, true, "a".repeat(64)),
            DialoguePackReadiness.decode(id, payload()),
        )
        assertFalse(DialoguePackReadiness.decode(id, payload(flag = 0))!!.ready)
        assertNull(DialoguePackReadiness.decode(UUID.randomUUID(), payload()))
        assertNull(DialoguePackReadiness.decode(id, payload(revision = 0)))
        assertNull(DialoguePackReadiness.decode(id, payload(flag = 2)))
        assertNull(DialoguePackReadiness.decode(id, payload(pin = "A".repeat(64))))
        assertNull(DialoguePackReadiness.decode(id, payload().copyOf(89)))
        assertNull(DialoguePackReadiness.decode(id, payload().copyOf(91)))
    }
}
