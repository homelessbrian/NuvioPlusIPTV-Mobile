package com.nuvio.app.features.livetv.sports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest

/** A finished page belongs to the selection that requested it, including empty pages. */
internal class PreparedSportsPage<Q, P>(val query: Q, val page: P) {
    fun forQuery(current: Q): P? = page.takeIf { query == current }
}

/** Preparing the first selection and every replacement stays away from the collector's thread. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <I, Q, P> Flow<I>.prepareSportsPages(
    queryOf: (I) -> Q,
    build: suspend (I) -> P,
): Flow<PreparedSportsPage<Q, P>> = mapLatest { input ->
    val query = queryOf(input)
    val page = build(input)
    currentCoroutineContext().ensureActive()
    PreparedSportsPage(query, page)
}.flowOn(Dispatchers.Default).buffer(0)

internal data class SportsDayQuery(
    val day: Int,
    val sport: String?,
    val dayStart: Long,
    val dayEnd: Long,
    val active: Set<String>,
    val filterToChannels: Boolean,
    val follows: SportsFollows,
)

internal class MySportsInputs(val known: List<SportsEvent>, val follows: SportsFollows, val now: Long)

internal suspend fun buildMySportsPage(
    input: MySportsInputs,
    isMine: (SportsEvent, SportsFollows) -> Boolean,
): MyTeamsPage {
    val context = currentCoroutineContext()
    // A read-only view keeps the existing builder independent while cancelling obsolete scans.
    // The builder's result lists own their storage and keep the original event objects.
    val known = object : AbstractList<SportsEvent>() {
        override val size: Int get() = input.known.size
        override fun get(index: Int): SportsEvent {
            val event = input.known[index]
            if (index % 64 == 0) context.ensureActive()
            return event
        }
    }
    val page = SportsRows.build(known, input.follows, input.now) { isMine(it, input.follows) }
    context.ensureActive()
    return page
}

/** Repository emissions are read-only snapshots, replaced rather than edited in place. */
internal class SportsDayInputs(
    val query: SportsDayQuery,
    val events: List<SportsEvent>,
    val settings: SportsSettings,
    val matches: Map<String, List<ChannelMatch>>,
    val now: Long,
) {
    val follows: SportsFollows get() = query.follows
    val active: Set<String> get() = query.active
    val filterToChannels: Boolean get() = query.filterToChannels
    val dayStart: Long get() = query.dayStart
    val dayEnd: Long get() = query.dayEnd
}

internal class SportsDaySections(
    val weekLong: List<SportsEvent> = emptyList(),
    val live: List<SportsEvent> = emptyList(),
    val upcoming: List<SportsEvent> = emptyList(),
    val finished: List<SportsEvent> = emptyList(),
)

internal class SportsDayPage(val sports: List<Sport>, val sections: SportsDaySections)

/** The phone's existing day membership, filtering and stable sort rules, prepared on a worker. */
internal suspend fun buildSportsDayPage(
    input: SportsDayInputs,
    isMine: (SportsEvent, SportsFollows) -> Boolean,
): SportsDayPage {
    val context = currentCoroutineContext()
    var work = 0
    fun checkpoint() { if (work++ % 64 == 0) context.ensureActive() }
    val now = input.now
    val matches = input.matches
    val follows = input.follows
    val dayStart = input.dayStart
    val dayEnd = input.dayEnd
    val dayEvents = input.events.filter { ev ->
        checkpoint()
        val onDay = when {
            ev.state == GameState.POST -> {
                val finished = if (ev.hasTime) ev.startMs else minOf(ev.endMs ?: ev.startMs, now)
                finished in dayStart until dayEnd
            }
            ev.hasTime -> ev.startMs in dayStart until dayEnd
            ev.state == GameState.PRE -> ev.startMs in dayStart until dayEnd
            else -> dayEnd > now && ev.startMs < dayEnd && (ev.endMs ?: (ev.startMs + 1)) >= dayStart
        }
        ev.leagueId in input.active && onDay &&
            (!input.filterToChannels || ev.state == GameState.POST || isMine(ev, follows) || matches[ev.id]?.isNotEmpty() == true)
    }
    val order = SportsCatalog.sportsOrder(input.settings)
    val sports = dayEvents.mapNotNull { checkpoint(); SportsCatalog.league(it.leagueId)?.sport }
        .distinct().sortedBy { order.indexOf(it).let { i -> if (i < 0) 99 else i } }
    val shown = if (input.query.sport == null) dayEvents else dayEvents.filter {
        checkpoint()
        SportsCatalog.league(it.leagueId)?.sport?.name == input.query.sport
    }
    val leagueRank = SportsCatalog.ordered(input.settings).mapIndexed { i, l -> l.id to i }.toMap()
    fun rank(ev: SportsEvent): Int { checkpoint(); return leagueRank[ev.leagueId] ?: 999 }
    fun mine(ev: SportsEvent): Boolean { checkpoint(); return isMine(ev, follows) }
    fun airingNow(ev: SportsEvent): Boolean = matches[ev.id]?.any { m ->
        checkpoint()
        m.program?.let { now >= it.startMs && now < it.stopMs } == true
    } == true
    val weekLong = shown.filter { checkpoint(); !it.hasTime && it.state == GameState.LIVE && !airingNow(it) }
    val weekIds = weekLong.mapTo(HashSet()) { checkpoint(); it.id }
    val live = shown.filter { checkpoint(); it.state == GameState.LIVE && it.id !in weekIds }
        .sortedWith(compareByDescending<SportsEvent> { mine(it) }.thenByDescending { matches[it.id]?.isNotEmpty() == true }.thenBy { rank(it) }.thenBy { it.startMs })
    val upcoming = shown.filter { checkpoint(); it.state == GameState.PRE || it.state == GameState.OFF }
        .sortedWith(compareBy<SportsEvent> { if (it.hasTime) it.startMs else Long.MIN_VALUE }.thenByDescending { mine(it) }.thenBy { rank(it) })
    val finished = shown.filter { checkpoint(); it.state == GameState.POST }
        .sortedWith(compareByDescending<SportsEvent> { it.startMs }.thenBy { rank(it) })
    context.ensureActive()
    return SportsDayPage(sports, SportsDaySections(weekLong, live, upcoming, finished))
}
