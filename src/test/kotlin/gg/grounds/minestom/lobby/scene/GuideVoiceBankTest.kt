package gg.grounds.minestom.lobby.scene

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GuideVoiceBankTest {
    @Test
    fun `exact bounded phrase metadata resolves aliases and pitch adjusted duration`() {
        val bank = assertNotNullBank(testGuideVoiceMetadata())
        assertEquals(14, bank.clips.size)
        val phrase = bank.clips.single { it.name == "bwa" }
        assertEquals("dialogue:guide/speech/bwa", phrase.key)
        assertEquals(600_000_000L, phrase.playbackNanos(1f))
        assertTrue(phrase.playbackNanos(.965f) in 621_761_000L..621_763_000L)
    }

    @Test
    fun `missing malformed unknown and unbounded metadata disable only optional voice`() {
        assertNull(GuideVoiceBank.load(null))
        assertNull(GuideVoiceBank.load("bad json".toByteArray()))
        assertNull(GuideVoiceBank.load(ByteArray(16385)))
        for (mutate in
            listOf<(com.google.gson.JsonObject) -> Unit>(
                { it.addProperty("schemaVersion", 2) },
                { it.addProperty("extra", true) },
                { it.getAsJsonObject("clips").remove("bwa") },
                { it.getAsJsonObject("clips").add("unknown", it.getAsJsonObject("clips")["bwa"]) },
                {
                    it.getAsJsonObject("clips")
                        .getAsJsonObject("bwa")
                        .addProperty("kind", "farewell")
                },
                {
                    it.getAsJsonObject("clips")
                        .getAsJsonObject("bwa")
                        .addProperty("durationMillis", 599)
                },
                {
                    it.getAsJsonObject("clips")
                        .getAsJsonObject("bwa")
                        .addProperty("durationMillis", 1801)
                },
                {
                    it.getAsJsonObject("clips")
                        .getAsJsonObject("bwa")
                        .addProperty("durationMillis", 600.5)
                },
                {
                    it.getAsJsonObject("clips")
                        .getAsJsonObject("hello01")
                        .addProperty("durationMillis", 1101)
                },
            )) {
            val json =
                JsonParser.parseString(testGuideVoiceMetadata().toString(Charsets.UTF_8))
                    .asJsonObject
            mutate(json)
            assertNull(GuideVoiceBank.load(json.toString().toByteArray()), json.toString())
        }
    }

    @Test
    fun `packaged voice metadata is accepted by the published SDK and app when present`() {
        val assets = StageDialogueAssets.load()
        val data = assets.entries()[GuideVoiceBank.PATH]
        if (data != null) assertNotNullBank(data) else assertNull(GuideVoiceBank.load(data))
    }

    private fun assertNotNullBank(bytes: ByteArray): GuideVoiceBank =
        GuideVoiceBank.load(bytes).also { assertNotNull(it) }!!
}

internal fun testGuideVoiceMetadata(talkMillis: Int = 600, moodMillis: Int = 500): ByteArray {
    val clips = linkedMapOf<String, Any>()
    for (name in listOf("bwa", "li", "bup", "wee", "mwa", "v06", "v07", "v08", "v09", "v10")) clips[
        name] = mapOf("kind" to "speech", "durationMillis" to talkMillis)
    for ((kind, names) in
        mapOf(
            "greeting" to listOf("hello01", "hello02"),
            "farewell" to listOf("bye01", "bye02"),
        )) for (name in names) clips[name] = mapOf("kind" to kind, "durationMillis" to moodMillis)
    return com.google.gson
        .Gson()
        .toJson(mapOf("schemaVersion" to 1, "clips" to clips))
        .toByteArray()
}

internal fun testGuideVoiceBank(talkMillis: Int = 600, moodMillis: Int = 500) =
    checkNotNull(GuideVoiceBank.load(testGuideVoiceMetadata(talkMillis, moodMillis)))
