package com.nuvio.app.features.livetv.sports

import com.nuvio.app.features.livetv.model.DrmInfo
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvSettings
import com.nuvio.app.features.livetv.model.LiveUserState

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsWatchPolicyTest {
    private val now = 1_800_000_000_000L

    @Test fun `only live and timed starting-soon events are offered for live tuning`() {
        val events = listOf(
            event("post", GameState.POST), event("off", GameState.OFF),
            event("later", GameState.PRE, now + SportsWatchPolicy.STARTING_SOON_MS + 1),
            event("stale-pre", GameState.PRE, now - SportsWatchPolicy.STARTED_GRACE_MS - 1),
            event("untimed", GameState.PRE).copy(hasTime = false),
            event("soon", GameState.PRE, now + SportsWatchPolicy.STARTING_SOON_MS),
            event("starting", GameState.PRE, now - SportsWatchPolicy.STARTED_GRACE_MS),
            event("live", GameState.LIVE).copy(hasTime = false),
        )
        val result = SportsWatchPolicy.games(events, emptyMap(), emptyMap(), SportsSettings(), now)
        assertEquals(listOf("live", "starting", "soon"), result.map { it.event.id })
        assertTrue(result.all { it.channels.isEmpty() })
    }

    @Test fun `hidden leagues and disabled Sports never leak into the strip`() {
        val events = listOf(event("one"), event("two").copy(leagueId = "hidden"))
        assertEquals(listOf("one"), SportsWatchPolicy.games(events, emptyMap(), emptyMap(),
            SportsSettings(hiddenLeagues = setOf("hidden")), now).map { it.event.id })
        assertTrue(SportsWatchPolicy.games(events, emptyMap(), emptyMap(),
            SportsSettings(showInSidebar = false), now).isEmpty())
    }

    @Test fun `source ordering stays in the repository region order and duplicate copies stay single`() {
        val a = channel("a")
        val b = channel("b")
        val matches = mapOf("game" to listOf(match(b, 160), match(a, 80), match(b, 20)))
        val allowed = SportsWatchPolicy.allowedChannels(listOf(a, b), matches, rules())
        val result = SportsWatchPolicy.games(listOf(event("game")), matches, allowed, SportsSettings(), now).single()
        assertEquals(listOf("b", "a"), result.channels.map { it.channel.key })
        assertEquals(160, result.channels.first().score)
    }

    @Test fun `current playlist URL headers and DRM replace stale cached match data`() {
        val old = channel("same")
        val current = old.copy(url = "https://example.invalid/new", headers = mapOf("X-Test" to "new"),
            drm = DrmInfo("widevine", "https://example.invalid/license", mapOf("X-License" to "new")))
        val matches = mapOf("game" to listOf(match(old)))
        val allowed = SportsWatchPolicy.allowedChannels(listOf(current), matches, rules())
        val chosen = SportsWatchPolicy.games(listOf(event("game")), matches, allowed, SportsSettings(), now)
            .single().channels.single()
        assertSame(current, chosen.channel)
        assertEquals(current.headers, chosen.channel.headers)
        assertEquals(current.drm, chosen.channel.drm)
    }

    @Test fun `explicit broadcast wins over repository order and a higher scored source`() {
        val preferred = match(channel("preferred"), 160)
        val selected = match(channel("selected"), 80)
        val choices = listOf(preferred, selected)
        assertSame(preferred, SportsWatchPolicy.resolveMatch(choices) { it })
        assertSame(selected, SportsWatchPolicy.resolveMatch(choices, "selected") { it })
        assertSame(selected, SportsWatchPolicy.resolveMatch(choices.reversed(), "selected") { it })
    }

    @Test fun `removed broadcast cannot fall back even when a different game still offers its key`() {
        val other = match(channel("other"))
        val oldSelection = channel("selected")
        val stillKnown = mapOf(other.channel.key to other.channel, oldSelection.key to oldSelection)
        val resolvedKeys = ArrayList<String>()
        val result = SportsWatchPolicy.resolveMatch(listOf(other), oldSelection.key) {
            resolvedKeys += it.key
            stillKnown[it.key]
        }
        assertNull(result)
        assertTrue(resolvedKeys.isEmpty())
    }

    @Test fun `forbidden explicit broadcast does not silently use another allowed source`() {
        val allowed = channel("allowed")
        val locked = channel("locked").copy(groupId = "locked-group")
        val currentRules = rules(user = LiveUserState(lockedGroups = setOf(locked.groupId)),
            live = LiveTvSettings(parentalPin = "1234"))
        val choices = listOf(match(allowed), match(locked))
        assertNull(SportsWatchPolicy.resolveMatch(choices, locked.key) { it.takeIf(currentRules::permits) })
        assertSame(allowed, SportsWatchPolicy.resolveMatch(choices) { it.takeIf(currentRules::permits) }?.channel)
    }

    @Test fun `explicit source refresh keeps its match metadata and exact new request record`() {
        val old = channel("selected")
        val latest = old.copy(url = "https://example.invalid/renewed",
            headers = mapOf("X-Request" to "renewed"),
            drm = DrmInfo("widevine", "https://example.invalid/renewed-license", mapOf("X-License" to "renewed")))
        val selected = match(old, 80).copy(how = "Chosen broadcast", quality = "HD", catchup = true)
        val current = mapOf(latest.key to latest)
        val result = SportsWatchPolicy.resolveMatch(listOf(match(channel("first")), selected), old.key) { current[it.key] }
        assertSame(latest, result?.channel)
        assertEquals(selected.copy(channel = latest), result)
    }

    @Test fun `disabled addon broadcast does not switch to an available playlist broadcast`() {
        val playlist = channel("playlist")
        val addon = channel("addon:abc:event:0").copy(sourceId = SportsAddonSource.SOURCE_ID)
        val choices = listOf(match(playlist), match(addon))
        val enabled = SportsWatchPolicy.allowedChannels(listOf(playlist), mapOf("game" to choices),
            rules(addons = setOf("addon:abc:")))
        assertSame(addon, SportsWatchPolicy.resolveMatch(choices, addon.key) { enabled[it.key] }?.channel)
        val disabled = SportsWatchPolicy.allowedChannels(listOf(playlist), mapOf("game" to choices), rules())
        assertNull(SportsWatchPolicy.resolveMatch(choices, addon.key) { disabled[it.key] })
        assertSame(playlist, SportsWatchPolicy.resolveMatch(choices) { disabled[it.key] }?.channel)
    }

    @Test fun `source adapter cannot change the selected channel identity during refresh`() {
        val selected = channel("selected")
        assertNull(SportsWatchPolicy.resolveMatch(listOf(match(selected)), selected.key) { channel("different") })
    }

    @Test fun `removed playlist source is not resurrected by a saved Sports match`() {
        val removed = channel("removed")
        val matches = mapOf("game" to listOf(match(removed)))
        val allowed = SportsWatchPolicy.allowedChannels(emptyList(), matches, rules())
        assertTrue(allowed.isEmpty())
        assertTrue(SportsWatchPolicy.games(listOf(event("game")), matches, allowed, SportsSettings(), now)
            .single().channels.isEmpty())
    }

    @Test fun `current unmatched playlist channel remains available to seed the first pane`() {
        val playing = channel("playing")
        val allowed = SportsWatchPolicy.allowedChannels(listOf(playing), emptyMap(), rules())
        assertSame(playing, allowed[playing.key])
    }

    @Test fun `manual event network team and league picks preserve hidden Sports sources`() {
        val hidden = channel("playlist|id|hidden")
        val user = LiveUserState(hiddenChannels = setOf(hidden.key), hiddenGroups = setOf(hidden.groupId))
        val settings = listOf(
            SportsSettings(eventChannels = mapOf("game" to "${hidden.key}|$now")),
            SportsSettings(networkChannels = mapOf("network" to hidden.key)),
            SportsSettings(teamChannels = mapOf("team" to hidden.key)),
            SportsSettings(leagueChannels = mapOf("league" to hidden.key)),
        )
        assertFalse(rules(user = user).permits(hidden))
        settings.forEach { assertTrue(rules(user = user, sports = it).permits(hidden)) }
    }

    @Test fun `manual hidden override cannot bypass group PIN or adult protection`() {
        val hidden = channel("hidden")
        val manual = SportsSettings(eventChannels = mapOf("game" to "${hidden.key}|$now"))
        val user = LiveUserState(hiddenGroups = setOf(hidden.groupId), lockedGroups = setOf(hidden.groupId))
        val pin = LiveTvSettings(parentalPin = "1234", lockAdultContent = true)
        assertFalse(rules(user, pin, manual).permits(hidden))
        assertTrue(rules(user, pin, manual, unlocked = setOf(hidden.groupId)).permits(hidden))
        assertFalse(rules(user, pin, manual, unlocked = setOf(hidden.groupId))
            .permits(hidden.copy(name = "Adults 18+")))
        assertFalse(rules(live = pin).permits(hidden.copy(group = "Adults")))
    }

    @Test fun `disabled PIN retains ordinary Sports behavior but not hidden channels`() {
        val ch = channel("channel")
        assertTrue(rules(user = LiveUserState(lockedGroups = setOf(ch.groupId)),
            live = LiveTvSettings(parentalPin = "")).permits(ch))
        assertFalse(rules(user = LiveUserState(hiddenChannels = setOf(ch.key)),
            live = LiveTvSettings(parentalPin = "")).permits(ch))
    }

    @Test fun `enabled Sports addons are allowed without entering the playlist store`() {
        val addon = channel("addon:abc:event:0").copy(sourceId = SportsAddonSource.SOURCE_ID)
        val matches = mapOf("game" to listOf(match(addon)))
        val allowed = SportsWatchPolicy.allowedChannels(emptyList(), matches,
            rules(addons = setOf("addon:abc:")))
        assertSame(addon, allowed[addon.key])
        assertTrue(SportsWatchPolicy.allowedChannels(emptyList(), matches,
            rules(addons = setOf("addon:ab:"))).isEmpty())
        assertTrue(SportsWatchPolicy.allowedChannels(emptyList(), matches, rules()).isEmpty())
    }

    @Test fun `addon matches still obey current parental and hidden-source rules`() {
        val addon = channel("addon:abc:event:0").copy(sourceId = SportsAddonSource.SOURCE_ID)
        val matches = mapOf("game" to listOf(match(addon)))
        val hidden = rules(user = LiveUserState(hiddenChannels = setOf(addon.key)), addons = setOf("addon:abc:"))
        val locked = rules(user = LiveUserState(lockedGroups = setOf(addon.groupId)),
            live = LiveTvSettings(parentalPin = "1234"), addons = setOf("addon:abc:"))
        assertTrue(SportsWatchPolicy.allowedChannels(emptyList(), matches, hidden).isEmpty())
        assertTrue(SportsWatchPolicy.allowedChannels(emptyList(), matches, locked).isEmpty())
    }

    @Test fun `score-delayed event objects pass through without refreshing or rewriting scores`() {
        val delayed = event("game").copy(status = "Delayed clock", note = "Delayed event")
        val result = SportsWatchPolicy.games(listOf(delayed), emptyMap(), emptyMap(),
            SportsSettings(hideScores = true, scoreDelaySeconds = 90), now).single()
        assertSame(delayed, result.event)
        assertEquals("Delayed clock", result.event.status)
    }

    @Test fun `Sports page rows retain their section and event order instead of sorting feed dates`() {
        val football = event("football", start = now - 60_000L)
        val next = event("next", GameState.PRE, now + 60_000L)
        val tournament = event("tournament", start = now - 3 * 86_400_000L).copy(hasTime = false)
        val current = SportsWatchPolicy.games(listOf(tournament, next, football), emptyMap(), emptyMap(),
            SportsSettings(), now).associateBy { it.event.id }
        val rows = SportsWatchPolicy.projectRows(listOf(
            Triple("live", "Live Now", listOf(football)),
            Triple("upcoming", "Coming Up", listOf(next)),
            Triple("week", "This Week", listOf(tournament)),
        ), current)
        assertEquals(listOf("live", "upcoming", "week"), rows.map { it.key })
        assertEquals(listOf("football", "next", "tournament"), rows.flatMap { it.games }.map { it.event.id })
    }

    @Test fun `arranged My Sports league rows use native row order and omit results and empty rows`() {
        val nfl = event("nfl", start = now - 60_000L).copy(leagueId = "football/nfl")
        val nba = event("nba", start = now - 120_000L).copy(leagueId = "basketball/nba")
        val result = event("result", GameState.POST).copy(leagueId = "hockey/nhl")
        val follows = SportsFollows(pins = listOf(nba.leagueId, result.leagueId, nfl.leagueId),
            order = listOf("l:${nfl.leagueId}", "l:${result.leagueId}", "l:${nba.leagueId}"))
        val page = SportsRows.build(listOf(nba, result, nfl), follows, now) { true }
        val current = SportsWatchPolicy.games(listOf(nba, result, nfl), emptyMap(), emptyMap(),
            SportsSettings(), now).associateBy { it.event.id }
        val rows = SportsWatchPolicy.projectRows(page.rows.map { Triple(it.key, it.title, it.events) }, current)
        assertEquals(listOf("up", "l:football/nfl", "l:basketball/nba"), rows.map { it.key })
        assertEquals(listOf("nba", "nfl"), rows.first().games.map { it.event.id })
        assertTrue(rows.all { it.games.isNotEmpty() })
    }

    @Test fun `schedule row records are replaced with the current score-delayed event without regrouping`() {
        val feed = event("game").copy(status = "feed score")
        val delayed = feed.copy(status = "delayed score")
        val missing = event("only-in-schedule")
        val current = SportsWatchPolicy.games(listOf(delayed), emptyMap(), emptyMap(), SportsSettings(), now)
            .associateBy { it.event.id }
        val rows = SportsWatchPolicy.projectRows(listOf(Triple("chosen", "Chosen", listOf(feed, missing, feed))), current)
        assertEquals(listOf("game"), rows.single().games.map { it.event.id })
        assertSame(delayed, rows.single().games.single().event)
    }

    private fun rules(
        user: LiveUserState = LiveUserState(),
        live: LiveTvSettings = LiveTvSettings(),
        sports: SportsSettings = SportsSettings(),
        addons: Set<String> = emptySet(),
        unlocked: Set<String> = emptySet(),
    ) = SportsWatchRules(user, live, sports, addons, unlocked)

    private fun event(id: String, state: GameState = GameState.LIVE, start: Long = now) =
        SportsEvent(id, "league", start, true, state, id, null, null, null, null)

    private fun channel(key: String) = LiveChannel(key, "playlist", "Fixture", key, null, null, null,
        "sports", "Sports", 1, "https://example.invalid/$key")

    private fun match(channel: LiveChannel, score: Int = 100) = ChannelMatch(channel, score, "Fixture", null, null, false)

}
