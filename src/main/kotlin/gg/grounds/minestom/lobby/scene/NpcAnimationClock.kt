package gg.grounds.minestom.lobby.scene

/** Stagger regular work without shifting the elapsed time used to sample a pose. */
internal class NpcAnimationClock(private val seed: Int) {
    private var lastRendered = Long.MIN_VALUE
    private var lastAdvanced = Long.MIN_VALUE
    private var lastInterval = 0L

    fun advance(time: Long, interval: Long, force: Boolean): Boolean {
        val seeking = time < lastAdvanced
        lastAdvanced = time
        if (
            !force &&
                !seeking &&
                lastRendered != Long.MIN_VALUE &&
                time >= lastRendered &&
                time - lastRendered < interval &&
                (interval != lastInterval ||
                    bucket(time, interval) == bucket(lastRendered, interval))
        )
            return false
        lastRendered = time
        lastInterval = interval
        return true
    }

    private fun bucket(time: Long, interval: Long): Long {
        val phase = Math.floorMod(seed, (interval / 50).toInt()) * 50L
        // Divide before subtracting the phase so the arithmetic cannot overflow on a large seek.
        return Math.floorDiv(time, interval) - if (Math.floorMod(time, interval) < phase) 1 else 0
    }
}
