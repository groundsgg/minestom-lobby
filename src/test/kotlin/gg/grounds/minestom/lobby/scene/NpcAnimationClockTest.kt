package gg.grounds.minestom.lobby.scene

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NpcAnimationClockTest {
    @Test
    fun `near phases retain five updates per second with one complete pose per tick`() {
        val clocks = (0..3).map(::NpcAnimationClock)
        clocks.forEach { assertTrue(it.advance(0, 200, true)) }
        val counts = IntArray(4)
        for (tick in 1..80) {
            val due = clocks.indices.filter { clocks[it].advance(tick * 50L, 200, false) }
            assertEquals(listOf(tick % 4), due)
            counts[due.single()]++
        }
        assertArrayEquals(intArrayOf(20, 20, 20, 20), counts)
    }

    @Test
    fun `mid and far phases retain their cadence including negative identity hashes`() {
        for (interval in listOf(250L, 500L)) {
            for (seed in listOf(0, 1, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
                val clock = NpcAnimationClock(seed)
                assertTrue(clock.advance(0, interval, true))
                val times = (1..100).map { it * 50L }.filter { clock.advance(it, interval, false) }
                assertEquals((5000 / interval).toInt(), times.size)
                assertTrue(times.zipWithNext().all { (a, b) -> b - a == interval })
            }
        }
    }

    @Test
    fun `forced lifecycle state is immediate without repeating on the same timestamp`() {
        val clock = NpcAnimationClock(1)
        assertTrue(clock.advance(0, 200, true))
        assertTrue(clock.advance(25, 200, true))
        assertFalse(clock.advance(25, 200, false))
        assertTrue(clock.advance(50, 200, false))
        assertFalse(clock.advance(50, 200, false))
        assertFalse(clock.advance(200, 200, false))
        assertTrue(clock.advance(250, 200, false))
    }

    @Test
    fun `distance changes honor the last render before returning to the phased cadence`() {
        val clock = NpcAnimationClock(0)
        assertTrue(clock.advance(0, 500, true))
        assertFalse(clock.advance(250, 500, false))
        assertTrue(clock.advance(300, 200, false))
        assertFalse(clock.advance(600, 500, false))
        assertTrue(clock.advance(800, 500, false))
        assertTrue(clock.advance(1000, 500, false))
        assertFalse(clock.advance(1250, 500, false))
        assertTrue(clock.advance(1500, 500, false))
    }

    @Test
    fun `rewind within a skipped interval immediately restores the requested pose`() {
        val clock = NpcAnimationClock(0)
        assertTrue(clock.advance(0, 200, true))
        assertFalse(clock.advance(150, 200, false))
        assertTrue(clock.advance(100, 200, false))
        assertFalse(clock.advance(100, 200, false))
        assertTrue(clock.advance(200, 200, false))
    }

    @Test
    fun `backward seek and late ticks render one current state without a catchup burst`() {
        val clock = NpcAnimationClock(1)
        assertTrue(clock.advance(1000, 200, true))
        assertTrue(clock.advance(150, 200, false))
        assertFalse(clock.advance(150, 200, false))
        assertTrue(clock.advance(250, 200, false))
        assertTrue(clock.advance(5555, 200, false))
        assertFalse(clock.advance(5555, 200, false))
        assertFalse(clock.advance(5649, 200, false))
        assertTrue(clock.advance(5650, 200, false))
    }
}
