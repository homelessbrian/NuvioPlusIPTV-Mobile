package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.sports.FollowTeamSearchResult
import com.nuvio.app.features.livetv.sports.GameState
import com.nuvio.app.features.livetv.sports.Side
import com.nuvio.app.features.livetv.sports.SportsCatalog
import com.nuvio.app.features.livetv.sports.SportsEvent
import com.nuvio.app.features.livetv.sports.TeamInfo
import com.nuvio.app.features.livetv.sports.knownTeamSearchResults
import com.nuvio.app.features.livetv.sports.leagueTeamSearchResults
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FollowTeamSearchTest {
    private fun team(id: String, league: String = "soccer/eng.1", name: String = "Team $id") =
        TeamInfo(league, id, name, name, "T$id", null, null, null, null)

    private fun event(id: String, away: TeamInfo?, home: TeamInfo?) = SportsEvent(
        id, "soccer/eng.1", 0, true, GameState.PRE, id, null, null,
        away?.let { Side(it, null, null, false, false) },
        home?.let { Side(it, null, null, false, true) },
    )

    @Test
    fun knownSearchMatchesOriginalRulesOrderingAndFirstFamilyOccurrence() = runTest {
        val first = team("1", name = "United").copy(fullName = "Manchester United", abbr = "MUN")
        val events = listOf(
            event("first", first, team("2", name = "München")),
            event("duplicate", first.copy(leagueId = "soccer/uefa.champions"), team("1", "football/nfl", "United")),
            event("fields", team("3", name = "ShortOnly").copy(fullName = "Something else", abbr = "UXX"), null),
            event("missing", null, null),
        )
        for (query in listOf("", " ", "a", "un", " UNITED ", "mun", "mu", "MÜN", "ShortOnly", "UX", "UXX", "nothing")) {
            val q = query.trim()
            val expected = if (q.length < 2) emptyList() else events.asSequence()
                .flatMap { it.sides.asSequence() }.map { it.team }
                .filter { it.fullName.contains(q, true) || it.name.contains(q, true) || it.abbr.equals(q, true) }
                .distinctBy { SportsCatalog.teamFamily(it.leagueId) + "|" + it.id }
                .take(30).toList()
            val result = knownTeamSearchResults(events, flowOf(query), StandardTestDispatcher(testScheduler)).first()
            assertEquals(query, result.query)
            assertEquals(expected, result.teams, query)
            expected.zip(result.teams).forEach { (before, after) -> assertSame(before, after) }
        }
        assertSame(first, knownTeamSearchResults(events, flowOf("un"), StandardTestDispatcher(testScheduler)).first().teams.first())
    }

    @Test
    fun knownSearchKeepsThirtyResultCapAcrossBothSidesAndDuplicates() = runTest {
        val events = (0 until 80).map { i ->
            event("$i", team("${i * 2}"), team("${i * 2 + 1}"))
        }
        val result = knownTeamSearchResults(events, flowOf("team"), StandardTestDispatcher(testScheduler)).first()
        assertEquals((0 until 30).map { it.toString() }, result.teams.map { it.id })
        assertEquals(30, result.teams.size)
    }

    @Test
    fun leagueSheetPreservesRawSubstringMatchingOrderDuplicatesAndNoCap() = runTest {
        val teams = listOf(
            team("one", name = "ShortOnly").copy(fullName = "Other", abbr = "ABCD"),
            team("two", name = "München"),
        ) + (0 until 50).map { team("$it") } + team("0")
        for (filter in listOf("", " ", "  ", "a", "bc", "ShortOnly", "MÜN", "team", " Team ", "missing")) {
            val expected = teams.filter { filter.isBlank() || it.fullName.contains(filter, true) || it.abbr.contains(filter, true) }
            val result = leagueTeamSearchResults(teams, flowOf(filter), StandardTestDispatcher(testScheduler)).first()
            assertEquals(expected, result.teams, filter)
            expected.zip(result.teams).forEach { (before, after) -> assertSame(before, after) }
            if (filter.isBlank()) assertSame(teams, result.teams)
        }
        assertEquals(51, leagueTeamSearchResults(teams, flowOf("team"), StandardTestDispatcher(testScheduler)).first().teams.size)
    }

    @Test
    fun resultTagsHideOldQueriesIncludingWhitespaceChanges() {
        val items = listOf(team("1"))
        val result = FollowTeamSearchResult("team", items)
        assertSame(items, result.forQuery("team"))
        assertNull(result.forQuery("teamm"))
        assertNull(result.forQuery(" team "))
    }

    @Test
    fun supersededQueuedSearchPublishesOnlyLatestQuery() = runTest {
        val queries = MutableStateFlow("old")
        val results = mutableListOf<FollowTeamSearchResult>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            knownTeamSearchResults(listOf(event("one", team("1", name = "New"), null)), queries,
                StandardTestDispatcher(testScheduler)).collect { results += it }
        }
        queries.value = "new"
        runCurrent()
        assertEquals(listOf("new"), results.map { it.query })
        assertEquals(listOf("1"), results.single().teams.map { it.id })
        job.cancel()
    }

    @Test
    fun cancellationStopsLongKnownAndLeagueScansWithoutPublication() = runTest {
        for (knownSearch in listOf(true, false)) {
            var reads = 0
            var published = 0
            lateinit var job: Job
            val item = team("1", name = "No match")
            val events = object : AbstractList<SportsEvent>() {
                override val size = 100_000
                override fun get(index: Int): SportsEvent {
                    reads++
                    if (index == 1) job.cancel()
                    return event("$index", item, null)
                }
            }
            val teams = object : AbstractList<TeamInfo>() {
                override val size = 100_000
                override fun get(index: Int): TeamInfo {
                    reads++
                    if (index == 1) job.cancel()
                    return item
                }
            }
            job = backgroundScope.launch {
                val result = if (knownSearch) knownTeamSearchResults(events, flowOf("absent"), StandardTestDispatcher(testScheduler))
                    else leagueTeamSearchResults(teams, flowOf("absent"), StandardTestDispatcher(testScheduler))
                result.collect { published++ }
            }
            runCurrent()
            assertTrue(job.isCancelled)
            assertTrue(reads <= 65, "Read $reads rows after cancellation")
            assertEquals(0, published)
        }
    }
}
