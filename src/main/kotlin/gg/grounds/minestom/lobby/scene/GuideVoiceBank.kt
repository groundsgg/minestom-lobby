package gg.grounds.minestom.lobby.scene

import kotlin.math.ceil

internal data class GuideVoiceClip(val name: String, val kind: String, val durationMillis: Int) {
    val key = "dialogue:guide/$kind/$name"

    fun playbackNanos(pitch: Float): Long = ceil(durationMillis * 1_000_000.0 / pitch).toLong()
}

internal class GuideVoiceBank private constructor(val clips: List<GuideVoiceClip>) {
    companion object {
        const val PATH = "assets/dialogue/guide/voice-bank.json"
        private val speech =
            listOf("bwa", "li", "bup", "wee", "mwa", "v06", "v07", "v08", "v09", "v10")
        private val expected =
            speech.associateWith { "speech" } +
                listOf("hello01", "hello02").associateWith { "greeting" } +
                listOf("bye01", "bye02").associateWith { "farewell" }

        fun load(bytes: ByteArray?): GuideVoiceBank? {
            if (bytes == null || bytes.size > 16384) return null
            return try {
                val root =
                    com.google.gson.JsonParser.parseString(bytes.toString(Charsets.UTF_8))
                        .asJsonObject
                require(root.keySet() == setOf("schemaVersion", "clips"))
                require(root["schemaVersion"].asJsonPrimitive.isNumber)
                require(root["schemaVersion"].asBigDecimal.intValueExact() == 1)
                val clips = root.getAsJsonObject("clips")
                require(clips.keySet() == expected.keys)
                val parsed =
                    expected.map { (name, kind) ->
                        val clip = clips.getAsJsonObject(name)
                        require(clip.keySet() == setOf("kind", "durationMillis"))
                        require(clip["kind"].asString == kind)
                        require(clip["durationMillis"].asJsonPrimitive.isNumber)
                        val duration = clip["durationMillis"].asBigDecimal.intValueExact()
                        require(duration in if (kind == "speech") 600..1800 else 450..1100)
                        GuideVoiceClip(name, kind, duration)
                    }
                GuideVoiceBank(java.util.List.copyOf(parsed))
            } catch (_: Exception) {
                null
            }
        }
    }
}
