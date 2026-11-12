package com.nuvio.app.features.livetv.calendar

import com.nuvio.app.features.livetv.sports.LeagueKind
import com.nuvio.app.features.livetv.sports.SportsCatalog
import com.nuvio.app.features.livetv.sports.SportsEvent
import com.nuvio.app.features.livetv.sports.SportsFeeds
import com.nuvio.app.features.livetv.sports.SportsFollows
import com.nuvio.app.features.livetv.sports.SportsDates

/**
 * Games for the teams you follow (not whole leagues, which would bury your shows), from a month
 * back to four months ahead. Leagues without teams (golf, racing, tennis, fights) are the
 * exception: their events show when you follow the league. They show on the Calendar as the same team-colour cards Sports uses.
 */
internal object CalendarGames {
    private const val DAY = 24 * 3_600_000L

    fun entries(known: List<SportsEvent>, follows: SportsFollows, now: Long): List<CalendarEntry> {
        val eventLeagues = (follows.leagues + follows.pins).filterTo(HashSet()) { SportsCatalog.league(it)?.kind == LeagueKind.EVENT }
        if (follows.teams.isEmpty() && eventLeagues.isEmpty()) return emptyList()
        val today = SportsDates.dayStart(0)
        return known.mapNotNull { ev ->
            val mine = ev.sides.firstOrNull { follows.followsTeam(ev.leagueId, it.team.id) }
            if (mine == null && ev.leagueId !in eventLeagues) return@mapNotNull null
            val day = SportsDates.dayOf(ev.startMs)
            if (day < today - 30 * DAY || day > today + 120 * DAY) return@mapNotNull null
            CalendarEntry(
                key = "game|${ev.id}", kind = CalendarKind.GAME, contentId = "game|${ev.id}", type = "game",
                title = ev.title, poster = mine?.team?.logo ?: SportsFeeds.leagueLogos[ev.leagueId], background = null,
                airMs = ev.startMs.takeIf { ev.hasTime }, dayMs = day,
                network = ev.broadcasts.firstOrNull(), eventId = ev.id,
            )
        }.sortedWith(compareBy<CalendarEntry> { it.dayMs }.thenBy { it.airMs ?: Long.MAX_VALUE })
    }
}
