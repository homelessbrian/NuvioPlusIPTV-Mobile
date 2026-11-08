package com.nuvio.app.features.livetv.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
internal fun rememberCalendarUpcomingDays(entries: List<CalendarEntry>, today: Long): List<Pair<Long, List<CalendarEntry>>> =
    remember(entries, today) {
        entries.filter { it.dayMs >= today }.groupBy { it.dayMs }.toList().sortedBy { it.first }
    }

/** Day selection only changes the lookup, not the entries grouped for the grid. */
@Composable
internal fun rememberCalendarEntriesByDay(entries: List<CalendarEntry>): Map<Long, List<CalendarEntry>> =
    remember(entries) { entries.groupBy { it.dayMs } }
