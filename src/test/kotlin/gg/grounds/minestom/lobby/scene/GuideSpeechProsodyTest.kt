package gg.grounds.minestom.lobby.scene

import gg.grounds.dialogue.core.text.Graphemes
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GuideSpeechProsodyTest {
    private fun breaks(text: String, locale: String = "en") =
        GuideSpeechProsody.boundaries(text, locale)

    @Test
    fun `punctuation runs include closing quotes and classify one pause`() {
        val text = "Hello, friend; listen: Really?!” Yes... Continue — onward."
        val points = breaks(text)
        assertEquals(listOf(120, 180, 180, 240, 300, 300, 240), points.map { it.pauseMillis })
        assertEquals(Graphemes.split("Hello, friend; listen: Really?!”").size, points[3].count)
        assertTrue(points[4].hesitation)
        assertTrue(points[5].hesitation)
        assertEquals(Graphemes.split(text).size, points.last().count)
    }

    @Test
    fun `German and CJK closing quotes remain part of their punctuation run`() {
        for ((text, prefix) in
            listOf("„Wirklich?!“ Weiter." to "„Wirklich?!“", "「你好！」「再见。」" to "「你好！」")) {
            assertEquals(Graphemes.split(prefix).size, breaks(text, "de").first().count)
        }
    }

    @Test
    fun `numbers internal dots urls initials and common abbreviations remain connected`() {
        for ((locale, text) in
            listOf(
                "en" to "Dr. A. Smith uses e.g. v1.2.3 at 12:30, 3.14 and https://a.b/c. Done!",
                "de" to "Dr. A. Meier nutzt z.B. bzw. d.h. 1.000 und 3,14. Fertig!",
            )) {
            val points = breaks(text, locale)
            assertEquals(if (locale == "en") 3 else 2, points.size, text)
            assertEquals(
                listOf(120, 240, 240).takeIf { locale == "en" } ?: listOf(240, 240),
                points.map { it.pauseMillis },
            )
        }
    }

    @Test
    fun `URL queries and email token punctuation never create interior pauses`() {
        val points =
            breaks("Open https://a.b:8443/x?q=one,two;v=2.4 and mail a.b@example.com. Done!")
        assertEquals(listOf(240, 240), points.map { it.pauseMillis })
    }

    @Test
    fun `unicode grapheme counts and CJK stops match the public SDK`() {
        val text = "é👩‍👩‍👧‍👦🇩🇪，你好。次！"
        val points = breaks(text)
        assertEquals(listOf(4, 7, 9), points.map { it.count })
        assertEquals(listOf(120, 240, 240), points.map { it.pauseMillis })
    }

    @Test
    fun `short soft clauses coalesce while sentence boundaries remain`() {
        val text = "a, b, c. " + "Long phrase ".repeat(8)
        val windows = GuideSpeechProsody.windows(text, "en", 35_000_000L, 600_000_000L)
        assertEquals(2, windows.size)
        assertEquals(Graphemes.split("a, b, c.").size, windows.first().endCount)
        assertEquals(240_000_000L, windows[1].pauseNanos)
    }
}
