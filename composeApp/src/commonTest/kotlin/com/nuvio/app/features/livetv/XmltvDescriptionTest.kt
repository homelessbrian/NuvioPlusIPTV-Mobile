package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.data.XmltvParser
import com.nuvio.app.features.livetv.model.EpgProgram
import kotlin.test.Test
import kotlin.test.assertEquals

class XmltvDescriptionTest {
    @Test
    fun descriptionsMatchOriginalCompositionAcrossBlankUnicodeAndTruncatedFields() {
        val fields = listOf(
            null, "", " \t\r\n", "\u00a0\u2003", "  text  ", "Café 日本 🎬 & < >",
            "\u200b", "first\nsecond", "x".repeat(799), "x".repeat(800), "x".repeat(801),
        )
        for (subtitle in fields) {
            for (description in fields) {
                // The prior parser's composition is the oracle, including its UTF-16 limit.
                val full = listOfNotNull(subtitle?.takeIf { it.isNotBlank() }, description?.takeIf { it.isNotBlank() })
                    .joinToString("\n").ifBlank { null }
                val expected = full?.let { if (it.length > 800) it.take(800).trimEnd() + "…" else it }
                val xml = guide(field("sub-title", subtitle) + field("desc", description))
                for (chunk in listOf(1, 7, xml.length)) {
                    assertEquals(expected, parse(xml, chunk).single().description, "chunk=$chunk, subtitle=$subtitle, description=$description")
                }
            }
        }
    }

    @Test
    fun keepsFirstDescriptionFieldsAndOnscreenEpisodePrecedence() {
        val xml = guide(
            "<sub-title> First subtitle </sub-title><sub-title>Ignored subtitle</sub-title>" +
                "<desc>First &amp; café description</desc><desc>Ignored description</desc>" +
                "<episode-num system=\"xmltv_ns\">0.1.</episode-num>" +
                "<episode-num system=\"onscreen\">S2 E3</episode-num>" +
                "<episode-num system=\"xmltv_ns\">4.5.</episode-num>",
        )
        for (chunk in listOf(1, 7, xml.length)) {
            val program = parse(xml, chunk).single()
            assertEquals(" First subtitle \nFirst & café description", program.description)
            assertEquals("S2 E3", program.episode)
            assertEquals(3_600_000L, program.stopMs - program.startMs)
        }
    }

    @Test
    fun retainsDescriptionsAndNextProgrammeAfterAnUnclosedField() {
        val xml = "<tv><programme channel=\"one\" start=\"20261108010000 +0200\" stop=\"20261108020000 +0200\">" +
            "<title>One</title><sub-title>Part</sub-title><desc>unfinished &amp; café" +
            "<programme channel=\"one\" start=\"20261108020000 +0200\" stop=\"20261108030000 +0200\">" +
            "<title>Two</title><desc>Second</desc></programme></tv>"
        for (chunk in listOf(1, 7, xml.length)) {
            val programs = parse(xml, chunk)
            assertEquals(listOf("One", "Two"), programs.map { it.title })
            assertEquals(listOf("Part\nunfinished & café", "Second"), programs.map { it.description })
        }
    }

    private fun parse(xml: String, chunk: Int): List<EpgProgram> =
        XmltvParser.parse(xml.chunked(chunk).asSequence(), Long.MIN_VALUE, Long.MAX_VALUE) { null }.programs["one"].orEmpty()

    private fun guide(fields: String): String =
        "<tv><programme channel=\"one\" start=\"20261108010000 +0200\" stop=\"20261108020000 +0200\">" +
            "<title>Title</title>$fields</programme></tv>"

    private fun field(name: String, text: String?): String = text?.let {
        "<$name>" + it.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</$name>"
    }.orEmpty()
}
