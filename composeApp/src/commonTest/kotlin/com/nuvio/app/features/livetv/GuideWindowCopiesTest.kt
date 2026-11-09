package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.data.GuideWindowCopies
import com.nuvio.app.features.livetv.model.EpgAssignment
import com.nuvio.app.features.livetv.model.EpgProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class GuideWindowCopiesTest {
    @Test
    fun aliasesPreserveTheirSelectedSourceAndWindowContents() {
        data class Selected(val guide: EpgAssignment, val programs: List<EpgProgram>)
        val shared = listOf(program(100, 200, "Shared"))
        val otherSource = listOf(program(100, 200, "Other source"))
        val selected = linkedMapOf(
            "automatic" to Selected(EpgAssignment("source-one", "guide"), shared),
            "pinned-alias" to Selected(EpgAssignment("source-one", "guide"), shared.toList()),
            "other-source" to Selected(EpgAssignment("source-two", "guide"), otherSource),
            "empty" to Selected(EpgAssignment("source-one", "empty-guide"), emptyList()),
        )
        val expected = selected.mapValues { (_, value) -> originalWindow(value.programs, 100, 300) }
            .filterValues { it.isNotEmpty() }
        val copies = GuideWindowCopies(100, 300)
        val actual = selected.mapValues { (_, value) ->
            copies.forGuide(value.guide.sourceId, value.guide.xmltvId, value.programs)
        }.filterValues { it.isNotEmpty() }

        assertEquals(expected, actual)
        assertSame(actual["automatic"], actual["pinned-alias"])
        assertSame(actual.getValue("automatic").single(), actual.getValue("pinned-alias").single())
        assertNotSame(actual["automatic"], actual["other-source"])
    }

    @Test
    fun preservesWindowBoundariesOrderOverlapsAndLightFields() {
        val programs = listOf(
            program(199, 350, "Overlap"),
            program(0, 100, "Ends at start"),
            program(-100, 500, "Spans the window"),
            program(300, 400, "Starts at end"),
            program(100, 200, "Starts at start"),
        )
        val actual = GuideWindowCopies(100, 300).forGuide("source", "guide", programs)
        assertEquals(originalWindow(programs, 100, 300), actual)
        assertEquals(listOf("Overlap", "Spans the window", "Starts at start"), actual.map { it.title })
        assertNotSame(programs.first(), actual.first())
    }

    @Test
    fun sourceAndGuideIdsCannotCollideAndCachesDoNotSurviveAnEmission() {
        val copies = GuideWindowCopies(100, 300)
        val first = copies.forGuide("a\u0000b", "c", listOf(program(100, 200, "First")))
        val second = copies.forGuide("a", "b\u0000c", listOf(program(100, 200, "Second")))
        assertEquals("First", first.single().title)
        assertEquals("Second", second.single().title)

        val nextEmission = GuideWindowCopies(100, 300)
            .forGuide("a\u0000b", "c", listOf(program(100, 200, "Updated")))
        assertEquals("Updated", nextEmission.single().title)
        assertNotSame(first, nextEmission)
    }

    @Test
    fun publishedWindowOwnsItsListAndDoesNotChangeWithTheInputBuffer() {
        val input = arrayListOf(program(100, 200, "Original"))
        val copies = GuideWindowCopies(100, 300)
        val published = copies.forGuide("source", "guide", input)
        input.clear()
        input += program(150, 250, "Replacement")

        assertEquals(listOf("Original"), published.map { it.title })
        assertSame(published, copies.forGuide("source", "guide", input))
    }

    private fun originalWindow(programs: List<EpgProgram>, from: Long, to: Long): List<EpgProgram> =
        programs.filter { it.stopMs > from && it.startMs < to }.map {
            EpgProgram(it.startMs, it.stopMs, it.title, episode = it.episode, icon = it.icon)
        }

    private fun program(start: Long, stop: Long, title: String): EpgProgram =
        EpgProgram(start, stop, title, description = "Full description", category = "Category", episode = "S2 E3", icon = "icon", year = 2026, people = listOf("Person"))
}
