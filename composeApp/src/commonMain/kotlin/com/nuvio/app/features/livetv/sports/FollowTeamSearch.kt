package com.nuvio.app.features.livetv.sports

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

/** A newly entered filter must never display the previous filter's rows. */
internal data class FollowTeamSearchResult(val query: String, val teams: List<TeamInfo>) {
    fun forQuery(currentQuery: String): List<TeamInfo>? = teams.takeIf { query == currentQuery }
}

/** Keep Mobile's known-game search scope, first matching occurrence, and result limit. */
internal fun knownTeamSearchResults(
    known: List<SportsEvent>,
    queries: Flow<String>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): Flow<FollowTeamSearchResult> = followTeamSearchResults(queries, dispatcher) { query ->
    val term = query.trim()
    if (term.length < 2) return@followTeamSearchResults emptyList()
    val context = currentCoroutineContext()
    val seen = HashSet<String>()
    val matches = ArrayList<TeamInfo>()
    for ((position, event) in known.withIndex()) {
        if (position % 64 == 0) context.ensureActive()
        fun add(team: TeamInfo?) {
            if (team != null && (team.fullName.contains(term, true) || team.name.contains(term, true) || team.abbr.equals(term, true))) {
                if (seen.add(SportsCatalog.teamFamily(team.leagueId) + "|" + team.id)) matches += team
            }
        }
        add(event.away?.team)
        if (matches.size == 30) break
        add(event.home?.team)
        if (matches.size == 30) break
    }
    matches
}

/** A league sheet searches the raw filter in full names/abbreviations without a cap. */
internal fun leagueTeamSearchResults(
    teams: List<TeamInfo>,
    queries: Flow<String>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): Flow<FollowTeamSearchResult> = followTeamSearchResults(queries, dispatcher) { filter ->
    if (filter.isBlank()) return@followTeamSearchResults teams
    val context = currentCoroutineContext()
    val matches = ArrayList<TeamInfo>()
    for ((position, team) in teams.withIndex()) {
        if (position % 64 == 0) context.ensureActive()
        if (team.fullName.contains(filter, true) || team.abbr.contains(filter, true)) matches += team
    }
    matches
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun followTeamSearchResults(
    queries: Flow<String>,
    dispatcher: CoroutineDispatcher,
    search: suspend (String) -> List<TeamInfo>,
): Flow<FollowTeamSearchResult> = queries.mapLatest { query ->
    withContext(dispatcher) { FollowTeamSearchResult(query, search(query)) }
}.buffer(0)
