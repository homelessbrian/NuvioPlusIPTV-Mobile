package com.nuvio.app.features.livetv.sports

import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SportsPagePreparationTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_800_000_000_000L
    private val start = now - 12 * hour
    private val leagues = listOf("football/nfl", "basketball/nba", "soccer/eng.1", "golf/pga", "unknown")

    private fun query(offset: Int = 0, sport: String? = null) = SportsDayQuery(
        offset, sport, start + offset * day, start + (offset + 1) * day,
        leagues.toSet(), false, SportsFollows(),
    )

    private fun event(id: String, league: String = "football/nfl", at: Long = now, timed: Boolean = true,
                      state: GameState = GameState.PRE, end: Long? = null, word: String? = null) =
        SportsEvent(id, league, at, timed, state, id, null, null, null, null, endMs = end, word = word)

    private fun match(id: String, from: Long = now - hour, until: Long = now + hour) = ChannelMatch(
        LiveChannel(id, "test", "Test", id, null, null, null, "test", "Test", 1, "https://example.invalid/test"),
        100, "Test", EpgProgram(from, until, id), null, false,
    )

    private fun inputs(events: List<SportsEvent>, sport: String? = null, filter: Boolean = false,
                       matches: Map<String, List<ChannelMatch>> = emptyMap(), follows: SportsFollows = SportsFollows(),
                       region: String = "US", active: Set<String> = leagues.toSet()) = SportsDayInputs(
        query(sport = sport).copy(active = active, filterToChannels = filter, follows = follows),
        events, SportsSettings(region = region), matches, now,
    )

    private fun mine(ev: SportsEvent, f: SportsFollows): Boolean = ev.leagueId in f.leagues || ev.word != null ||
        (ev.leagueId in f.pins && SportsCatalog.league(ev.leagueId)?.kind == LeagueKind.EVENT) ||
        ev.sides.any { f.followsTeam(ev.leagueId, it.team.id) } || f.reminders.any { it.eventId == ev.id }

    private suspend fun equivalent(input: SportsDayInputs): SportsDayPage {
        val expected = reference(input)
        val actual = buildSportsDayPage(input, ::mine)
        assertEquals(expected.sports, actual.sports)
        fun events(a: List<SportsEvent>, b: List<SportsEvent>) {
            assertEquals(a.size, b.size)
            a.indices.forEach { assertSame("Event order/identity at $it", a[it], b[it]) }
        }
        events(expected.sections.weekLong, actual.sections.weekLong)
        events(expected.sections.live, actual.sections.live)
        events(expected.sections.upcoming, actual.sections.upcoming)
        events(expected.sections.finished, actual.sections.finished)
        return actual
    }

    @Test fun `day boundaries timeless events and ended tournaments preserve full content`() = runBlocking {
        val events = listOf(
            event("before", at = start - 1), event("first", at = start), event("last", at = start + day - 1),
            event("next", at = start + day), event("old-live", at = start - day, state = GameState.LIVE),
            event("week", at = start - day, timed = false, state = GameState.LIVE, end = start + day),
            event("ended", at = start - day, timed = false, state = GameState.POST, end = now - 1),
            event("no-end", at = start - 1, timed = false, state = GameState.LIVE),
            event("off", at = now, timed = false, state = GameState.OFF),
            event("date-pre", at = now, timed = false), event("done", at = now, state = GameState.POST),
        )
        val page = equivalent(inputs(events))
        assertEquals(listOf("week", "no-end"), page.sections.weekLong.map { it.id })
        assertEquals(listOf("off", "date-pre", "first", "last"), page.sections.upcoming.map { it.id })
        assertEquals(listOf("done", "ended"), page.sections.finished.map { it.id })
    }

    @Test fun `channel filtering keeps finished followed word and reminder events`() = runBlocking {
        val events = listOf(event("unmatched"), event("matched"), event("league", "basketball/nba"),
            event("word", word = "\u0130"), event("reminder"), event("finished", state = GameState.POST))
        val follows = SportsFollows(leagues = setOf("basketball/nba"),
            reminders = listOf(SportsReminder("reminder", "Reminder", now)))
        val page = equivalent(inputs(events, filter = true, matches = mapOf("matched" to listOf(match("matched"))), follows = follows))
        assertEquals(events.drop(1).map { it.id }.toSet(), (page.sections.upcoming + page.sections.finished).map { it.id }.toSet())
    }

    @Test fun `a timeless event airing now belongs in live with end exclusive program bounds`() = runBlocking {
        val events = listOf(event("airing", timed = false, state = GameState.LIVE),
            event("stopped", timed = false, state = GameState.LIVE), event("starting", timed = false, state = GameState.LIVE))
        val page = equivalent(inputs(events, matches = mapOf("airing" to listOf(match("a")),
            "stopped" to listOf(match("b", until = now)), "starting" to listOf(match("c", from = now)))))
        assertEquals(listOf("stopped"), page.sections.weekLong.map { it.id })
        assertEquals(listOf("airing", "starting"), page.sections.live.map { it.id })
    }

    @Test fun `sport filtering unknown leagues stable ties duplicates and regional ordering remain unchanged`() = runBlocking {
        val duplicate = event("same", state = GameState.LIVE)
        val events = listOf(duplicate, event("nba", "basketball/nba", state = GameState.LIVE), duplicate,
            event("other", "unknown"), event("club", "soccer/eng.1"))
        for (region in listOf("US", "UK", "CA", "AU")) {
            equivalent(inputs(events, region = region))
            for (sport in Sport.entries) equivalent(inputs(events, sport = sport.name, region = region))
        }
        assertEquals(listOf("same", "same"), equivalent(inputs(events, sport = Sport.AMERICAN_FOOTBALL.name)).sections.live.map { it.id })
    }

    @Test fun `randomized whole pages match original phone selection and sorting`() = runBlocking {
        val random = Random(87)
        repeat(250) { revision ->
            val events = List(random.nextInt(120)) { i ->
                event("id-${random.nextInt(40)}", leagues.random(random), now + random.nextInt(-60, 60) * hour,
                    random.nextBoolean(), GameState.entries.random(random),
                    if (random.nextBoolean()) now + random.nextInt(-36, 36) * hour else null,
                    if (random.nextInt(8) == 0) "\u03a3" else null).copy(title = "revision-$revision-$i")
            }.shuffled(random)
            val matches = events.filter { random.nextBoolean() }.associate { it.id to listOf(match(it.id)) }
            val follows = SportsFollows(leagues = leagues.shuffled(random).take(random.nextInt(3)).toSet(),
                reminders = events.take(3).map { SportsReminder(it.id, it.title, it.startMs) })
            equivalent(inputs(events, Sport.entries.random(random).name.takeIf { random.nextBoolean() },
                random.nextBoolean(), matches, follows, listOf("US", "UK", "CA", "AU").random(random),
                leagues.shuffled(random).take(random.nextInt(leagues.size + 1)).toSet()))
        }
    }

    @Test fun `prepared empty pages are ready and changed day sport or filter rejects stale content`() {
        val query = query()
        val page = SportsDayPage(emptyList(), SportsDaySections())
        val prepared = PreparedSportsPage(query, page)
        assertSame(page, prepared.forQuery(query.copy()))
        assertNull(prepared.forQuery(query.copy(day = 1)))
        assertNull(prepared.forQuery(query.copy(sport = Sport.FOOTBALL.name)))
        assertNull(prepared.forQuery(query.copy(active = emptySet())))
        assertNull(prepared.forQuery(query.copy(filterToChannels = true)))
        assertNull(prepared.forQuery(query.copy(follows = SportsFollows(words = listOf("New follow")))))
    }

    @Test fun `midnight rollover rejects old actual day even when selected offset stays today`() = runBlocking {
        val query = query()
        val events = listOf(event("today", at = start), event("tomorrow", at = start + day))
        val input = inputs(events)
        val old = PreparedSportsPage(query, equivalent(input))
        val tomorrow = query.copy(dayStart = query.dayStart + day, dayEnd = query.dayEnd + day)
        assertEquals(query.day, tomorrow.day)
        assertNull(old.forQuery(tomorrow))
        assertNull(old.forQuery(query.copy(dayStart = query.dayStart + hour)))
        assertNull(old.forQuery(query.copy(dayEnd = query.dayEnd + hour)))
        val fresh = equivalent(SportsDayInputs(tomorrow, events, input.settings, input.matches, now + day))
        assertEquals(listOf("tomorrow"), fresh.sections.upcoming.map { it.id })
        assertEquals(listOf("today"), old.page.sections.upcoming.map { it.id })
    }

    @Test fun `both actual builders prepare their first page away from the screen collector`() {
        Executors.newSingleThreadExecutor { Thread(it, "sports-screen-test") }.asCoroutineDispatcher().use { screen ->
            runBlocking(screen) {
                val screenThread = Thread.currentThread()
                val ready = flowOf(inputs(listOf(event("test")))).prepareSportsPages({ it.query }) {
                    assertNotSame(screenThread, Thread.currentThread())
                    buildSportsDayPage(it, ::mine)
                }.first()
                assertSame(screenThread, Thread.currentThread())
                assertEquals(listOf("test"), ready.page.sections.upcoming.map { it.id })
                val follows = SportsFollows(pins = listOf("football/nfl"))
                val mySports = flowOf(MySportsInputs(listOf(event("pinned", state = GameState.LIVE)), follows, now))
                    .prepareSportsPages({ it.follows }) {
                        assertNotSame(screenThread, Thread.currentThread())
                        buildMySportsPage(it, ::mine)
                    }.first()
                assertSame(screenThread, Thread.currentThread())
                assertTrue(mySports.page.heroes.isEmpty())
                assertEquals(listOf(SportsRow.Kind.LEAGUE), mySports.page.rows.map { it.kind })
                assertNull(mySports.forQuery(follows.copy(words = listOf("New follow"))))
            }
        }
    }

    private suspend fun cancellationCheck(build: suspend (List<SportsEvent>) -> Unit) {
        val reads = AtomicInteger()
        lateinit var worker: Job
        val events = object : AbstractList<SportsEvent>() {
            override val size = 10_000
            override fun get(index: Int): SportsEvent {
                if (reads.incrementAndGet() == 65) worker.cancel()
                return event("event-$index")
            }
        }
        kotlinx.coroutines.coroutineScope {
            worker = launch(start = CoroutineStart.LAZY) { build(events) }
            worker.start()
            worker.join()
        }
        assertTrue(worker.isCancelled)
        assertEquals(65, reads.get())
    }

    @Test fun `cancelled actual day builder stops traversing an obsolete large snapshot`() = runBlocking {
        cancellationCheck { buildSportsDayPage(inputs(it), ::mine) }
    }

    @Test fun `cancelled actual my sports builder stops traversing an obsolete large snapshot`() = runBlocking {
        cancellationCheck { buildMySportsPage(MySportsInputs(it, SportsFollows(leagues = setOf("football/nfl")), now), ::mine) }
    }

    @Test fun `rapid selections cancel obsolete work and only current queries can be published`() = runBlocking {
        val requests = MutableStateFlow(0)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val done = CompletableDeferred<PreparedSportsPage<Int, Int>>()
        val published = ArrayList<Int>()
        val job = launch {
            requests.prepareSportsPages({ it }) { query ->
                if (query == 0) {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
                query
            }.collect { ready -> published += ready.query; if (ready.query == 250) done.complete(ready) }
        }
        try {
            withTimeout(5_000) { started.await() }
            repeat(250) { requests.value = it + 1 }
            val final = withTimeout(5_000) { done.await() }
            withTimeout(5_000) { cancelled.await() }
            assertEquals(250, final.forQuery(250))
            assertNull(final.forQuery(0))
            assertFalse(published.contains(0))
            assertEquals(250, published.last())
        } finally { job.cancelAndJoin() }
    }

    @Test fun `same query refresh replaces data and leaving the screen stops future requests`() = runBlocking {
        val requests = MutableStateFlow(0)
        val built = AtomicInteger()
        val initial = CompletableDeferred<Unit>()
        val refreshed = CompletableDeferred<Int>()
        val job = launch {
            requests.prepareSportsPages({ query() }) { built.incrementAndGet(); it }.collect {
                if (it.page == 0) initial.complete(Unit) else refreshed.complete(it.page)
            }
        }
        withTimeout(5_000) { initial.await() }
        requests.value = 7
        assertEquals(7, withTimeout(5_000) { refreshed.await() })
        job.cancelAndJoin()
        val count = built.get()
        requests.value = 8
        assertEquals(count, built.get())
    }

    /** Compatibility oracle: the pre-optimization phone composition's selection/sorting rules. */
    private fun reference(input: SportsDayInputs): SportsDayPage {
        val now = input.now
        val matches = input.matches
        val follows = input.follows
        val dayStart = input.dayStart
        val dayEnd = input.dayEnd
        val dayEvents = input.events.filter { ev ->
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
                (!input.filterToChannels || ev.state == GameState.POST || mine(ev, follows) || matches[ev.id]?.isNotEmpty() == true)
        }
        val order = SportsCatalog.sportsOrder(input.settings)
        val sports = dayEvents.mapNotNull { SportsCatalog.league(it.leagueId)?.sport }.distinct()
            .sortedBy { order.indexOf(it).let { i -> if (i < 0) 99 else i } }
        val shown = if (input.query.sport == null) dayEvents else dayEvents.filter {
            SportsCatalog.league(it.leagueId)?.sport?.name == input.query.sport
        }
        val leagueRank = SportsCatalog.ordered(input.settings).mapIndexed { i, l -> l.id to i }.toMap()
        fun rank(ev: SportsEvent) = leagueRank[ev.leagueId] ?: 999
        fun airingNow(ev: SportsEvent) = matches[ev.id]?.any { m -> m.program?.let { now >= it.startMs && now < it.stopMs } == true } == true
        val weekLong = shown.filter { !it.hasTime && it.state == GameState.LIVE && !airingNow(it) }
        val weekIds = weekLong.mapTo(HashSet()) { it.id }
        val live = shown.filter { it.state == GameState.LIVE && it.id !in weekIds }
            .sortedWith(compareByDescending<SportsEvent> { mine(it, follows) }.thenByDescending { matches[it.id]?.isNotEmpty() == true }.thenBy { rank(it) }.thenBy { it.startMs })
        val upcoming = shown.filter { it.state == GameState.PRE || it.state == GameState.OFF }
            .sortedWith(compareBy<SportsEvent> { if (it.hasTime) it.startMs else Long.MIN_VALUE }.thenByDescending { mine(it, follows) }.thenBy { rank(it) })
        val finished = shown.filter { it.state == GameState.POST }
            .sortedWith(compareByDescending<SportsEvent> { it.startMs }.thenBy { rank(it) })
        return SportsDayPage(sports, SportsDaySections(weekLong, live, upcoming, finished))
    }
}
