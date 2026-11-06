package gg.grounds.minestom.lobby

/** Build-time addon identity; Stage must never offer a different pack for these models. */
internal class NpcAddonBinding(private val sha1: String) {
    init {
        require(sha1.matches(Regex("[0-9a-f]{40}"))) { "Invalid packaged NPC addon SHA-1" }
    }

    fun requireMatches(configuredSha1: String) {
        require(sha1 == configuredSha1) {
            "Configured NPC addon differs from the packaged distribution"
        }
    }

    companion object {
        fun load(): NpcAddonBinding {
            val resource = "/gg/grounds/minestom/lobby/npc-addon-sha1.txt"
            val text =
                requireNotNull(NpcAddonBinding::class.java.getResourceAsStream(resource)) {
                        "NPC addon binding missing; build a qualified private NPC distribution"
                    }
                    .use { stream ->
                        val bytes = stream.readNBytes(42)
                        require(bytes.size <= 41) { "NPC addon binding exceeds size limit" }
                        bytes.toString(Charsets.US_ASCII).removeSuffix("\n")
                    }
            return NpcAddonBinding(text)
        }
    }
}
