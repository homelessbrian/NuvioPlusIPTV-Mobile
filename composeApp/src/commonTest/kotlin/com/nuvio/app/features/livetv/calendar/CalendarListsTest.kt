package com.nuvio.app.features.livetv.calendar

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.ControlledComposition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class CalendarListsTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val today = 20_000 * day

    private fun entry(key: String, dayMs: Long = today, airMs: Long? = null) = CalendarEntry(
        key = key, kind = CalendarKind.EPISODE, contentId = "show-$key", type = "series",
        title = key, poster = null, background = null, dayMs = dayMs, airMs = airMs,
    )

    @Test
    fun unrelatedRedrawsReusePreparedListsWithoutReadingEntriesAgain() = withComposition { c ->
        val entries = CountingEntries(List(500) { entry("$it", today + (it % 31 - 15) * day) })
        c.entries = entries
        c.today = today
        c.start()
        val upcoming = c.upcoming
        val byDay = c.byDay
        entries.reads = 0

        repeat(20) { c.recompose() }

        assertEquals(21, c.renders)
        assertSame(upcoming, c.upcoming)
        assertSame(byDay, c.byDay)
        assertEquals(0, entries.reads)
    }

    @Test
    fun projectionsMatchPreviousExpressionsAcrossSchedulesAndDateBoundaries() = withComposition { c ->
        val random = Random(79)
        c.entries = List(400) { i ->
            val date = today + (random.nextInt(36) - 18) * day + random.nextInt(2) * hour
            entry("${i % 71}", date, if (i % 4 == 0) null else date + random.nextInt(24) * hour)
                .copy(kind = CalendarKind.entries[i % CalendarKind.entries.size], watched = i % 3 == 0)
        }
        c.today = today
        c.start()

        for (dayOffset in listOf(-2 * day, 0L, 23 * hour, day, 25 * hour, 3 * day)) {
            c.today = today + dayOffset
            c.recompose()
            val expectedUpcoming = c.entries.filter { it.dayMs > c.today || it.dayMs == c.today }
                .groupBy { it.dayMs }.toList().sortedBy { it.first }

            assertEquals(expectedUpcoming, c.upcoming)
            for (date in c.entries.map { it.dayMs }.distinct() + Long.MAX_VALUE) {
                assertEquals(c.entries.filter { it.dayMs == date }, c.byDay[date].orEmpty())
            }
        }
    }

    @Test
    fun changedDayAndFilteredPoolRefreshMembershipAndEntryFields() = withComposition { c ->
        val previous = entry("previous", dayMs = today - day)
        val current = entry("current").copy(watched = true)
        val next = entry("next", dayMs = today + 23 * hour)
        c.entries = listOf(previous, current, next)
        c.today = today
        c.start()
        val byDay = c.byDay
        assertEquals(listOf(current, next), c.upcoming.flatMap { it.second })

        // Use the caller's next local midnight directly, including a 23-hour day.
        c.today = next.dayMs
        c.recompose()
        assertSame(byDay, c.byDay)
        assertEquals(listOf(next), c.upcoming.flatMap { it.second })

        // The caller supplies a new pool after on-TV hits, hidden-watched settings, or data change.
        val refreshed = next.copy(title = "Updated", watched = true, kind = CalendarKind.GAME)
        c.entries = listOf(refreshed)
        c.recompose()
        assertEquals(listOf(refreshed), c.upcoming.single().second)
        assertSame(refreshed, c.byDay[next.dayMs]!!.single())

        c.entries = emptyList()
        c.recompose()
        assertEquals(emptyList(), c.upcoming)
        assertEquals(emptyMap(), c.byDay)
    }

    private fun withComposition(block: (CalendarListComposition) -> Unit) {
        val composition = CalendarListComposition()
        try { block(composition) } finally { composition.close() }
    }
}

private class CountingEntries(private val entries: List<CalendarEntry>) : AbstractList<CalendarEntry>() {
    var reads = 0
    override val size: Int get() = entries.size
    override fun get(index: Int): CalendarEntry {
        reads++
        return entries[index]
    }
}

/** Exercises the production remember keys in Compose without rendering platform UI. */
private class CalendarListComposition {
    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) {}
        override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun remove(index: Int, count: Int) {}
        override fun onClear() {}
    }

    var entries: List<CalendarEntry> = emptyList()
    var today = 0L
    lateinit var upcoming: List<Pair<Long, List<CalendarEntry>>>
    lateinit var byDay: Map<Long, List<CalendarEntry>>
    var renders = 0
        private set
    private val redraw = mutableIntStateOf(0)
    private val recomposer = Recomposer(EmptyCoroutineContext)
    private val composition = ControlledComposition(EmptyApplier(), recomposer)

    fun start() {
        composition.setContent {
            redraw.intValue
            renders++
            upcoming = rememberCalendarUpcomingDays(entries, today)
            byDay = rememberCalendarEntriesByDay(entries)
        }
    }

    fun recompose() {
        redraw.intValue++
        composition.recordModificationsOf(setOf(redraw))
        val snapshot = Snapshot.takeMutableSnapshot(composition::recordReadOf, composition::recordWriteOf)
        val changed = try {
            val result = snapshot.enter { composition.recompose() }
            snapshot.apply().check()
            result
        } finally { snapshot.dispose() }
        if (changed) composition.applyChanges()
    }

    fun close() {
        composition.dispose()
        recomposer.cancel()
    }
}
