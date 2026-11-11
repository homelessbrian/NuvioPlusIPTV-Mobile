package com.nuvio.app.features.livetv.sports

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.parental.ParentalControls
import com.nuvio.app.features.livetv.ui.sports.isMine
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlin.concurrent.Volatile

internal data class SportsWatchPageSelection(val profileId: Int, val mine: Boolean, val day: Int = 0, val sport: String? = null)

/** Actual page context only, kept in memory and never carried into another profile. */
internal object SportsWatchSelection {
    val current = MutableStateFlow<SportsWatchPageSelection?>(null)
    fun select(mine: Boolean, day: Int = 0, sport: String? = null) {
        current.value = SportsWatchPageSelection(LiveTvPreferences.activeProfile.value, mine, day, sport)
    }
}

/** Screen-owned source adapter; its lifetime does not follow the app's retained navigation stack. */
internal class SportsWatchController(private val profileId: Int?) {
    private val repo = SportsRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var closed = false
    private var openedEventId: String? = null
    val settings = repo.settings

    init { repo.start(); repo.setViewing(true) }

    private fun rules(): SportsWatchRules = SportsWatchRules(
        LiveTvPreferences.currentUserState(), LiveTvPreferences.currentSettings(), settings.value,
        AddonRepository.uiState.value.addons.filter { it.isActive }.mapTo(HashSet()) {
            "addon:${it.manifestUrl.hashCode().toUInt().toString(16)}:"
        }, ParentalControls.unlocked.value,
    )

    private val permissionRules = combine(LiveTvPreferences.userState, LiveTvPreferences.settings,
        settings, AddonRepository.uiState, ParentalControls.unlocked) { user, live, sports, addons, unlocked ->
        SportsWatchRules(user, live, sports, addons.addons.filter { it.isActive }.mapTo(HashSet()) {
            "addon:${it.manifestUrl.hashCode().toUInt().toString(16)}:"
        }, unlocked)
    }

    private val allowed = combine(LiveTvRepository.displayChannels, repo.matches, permissionRules,
        LiveTvPreferences.activeProfile, ProfileRepository.state) { channels, matches, rules, _, _ ->
        if (!profileIsCurrent()) emptyMap() else SportsWatchPolicy.allowedChannels(channels, matches, rules)
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val allowedChannels: StateFlow<List<LiveChannel>> = combine(allowed, LiveTvPreferences.activeProfile,
        ProfileRepository.state) { allowed, _, _ -> if (profileIsCurrent()) allowed.values.toList() else emptyList() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val now = flow { while (true) { emit(LiveTvClock.nowMs()); delay(15_000) } }

    val games: StateFlow<List<SportsWatchGame>> = combine(repo.events, repo.matches, allowed, settings, now) {
        events, matches, allowed, settings, now ->
        if (profileIsCurrent()) SportsWatchPolicy.games(events, matches, allowed, settings, now) else emptyList()
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private data class PageInputs(val events: List<SportsEvent>, val known: List<SportsEvent>,
        val follows: SportsFollows, val settings: SportsSettings, val selected: SportsWatchPageSelection?)
    private val pageInputs = combine(repo.events, repo.known, repo.follows, settings, SportsWatchSelection.current) {
        events, known, follows, settings, selected -> PageInputs(events, known, follows, settings, selected)
    }

    val rows: StateFlow<List<SportsWatchRow>> = combine(pageInputs, repo.matches, allowed, now) {
        input, matches, allowed, now ->
        if (!profileIsCurrent()) emptyList() else {
            val selected = input.selected?.takeIf { it.profileId == profileId }
            val mine = selected?.mine ?: !input.follows.isEmpty
            val current = SportsWatchPolicy.games(input.events, matches, allowed, input.settings, now)
                .associateBy { it.event.id }
            if (mine) {
                val page = buildMySportsPage(MySportsInputs(input.known, input.follows, now), ::isMine)
                SportsWatchPolicy.projectRows(page.rows.map { Triple(it.key, it.title, it.events) }, current)
            } else {
                val day = selected?.day ?: 0
                val active = repo.activeLeagues(input.settings, input.follows).mapTo(HashSet()) { it.id } + SportsMatcher.OTHER_ID
                val query = SportsDayQuery(day, selected?.sport, repo.dayStart(day), repo.dayStart(day + 1), active,
                    input.settings.onlyOnMyChannels && LiveTvRepository.channels.value.isNotEmpty() && matches.isNotEmpty(), input.follows)
                val page = buildSportsDayPage(SportsDayInputs(query, input.events, input.settings, matches, now), ::isMine)
                SportsWatchPolicy.projectRows(listOf(
                    Triple("live", "Live Now", page.sections.live),
                    Triple("upcoming", if (day > 0) "Schedule" else "Coming Up", page.sections.upcoming),
                    Triple("week", "This Week", page.sections.weekLong),
                ), current)
            }
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun profileIsCurrent(): Boolean = !closed && profileId != null &&
        profileId == LiveTvPreferences.activeProfile.value && profileId ==
        (ProfileRepository.state.value.activeProfile?.profileIndex ?: ProfileRepository.activeProfileId)

    /** Selecting a game discovers its broadcasts; playback starts only on an explicit source. */
    fun openGame(eventId: String): Boolean {
        val event = currentEvent(eventId) ?: return false
        openedEventId?.takeIf { it != eventId }?.let(repo::closeGame)
        openedEventId = eventId
        repo.openGame(event)
        return true
    }

    fun resolve(eventId: String, channelKey: String? = null): ChannelMatch? {
        currentEvent(eventId) ?: return null
        val currentRules = rules()
        val match = SportsWatchPolicy.resolveMatch(repo.matches.value[eventId].orEmpty(), channelKey) {
            freshSource(it, currentRules)
        } ?: return null
        return match.takeIf { currentEvent(eventId) != null && mayPlay(it.channel) &&
            repo.matches.value[eventId].orEmpty().any { current -> current.channel.key == it.channel.key } }
    }

    fun mayPlay(channel: LiveChannel): Boolean = profileIsCurrent() &&
        freshSource(channel, rules()) == channel && profileIsCurrent()

    fun seedChannel(key: String?): LiveChannel? {
        if (key == null || !profileIsCurrent()) return null
        val source = LiveTvRepository.displayChannels.value.firstOrNull { it.key == key }
            ?: repo.matches.value.values.asSequence().flatten().firstOrNull { it.channel.key == key }?.channel
        return source?.let { freshSource(it, rules()) }?.takeIf { profileIsCurrent() }
    }

    private fun currentEvent(eventId: String): SportsEvent? {
        if (!profileIsCurrent()) return null
        return repo.events.value.firstOrNull { it.id == eventId }?.takeIf {
            settings.value.showInSidebar && it.leagueId !in settings.value.hiddenLeagues &&
                SportsWatchPolicy.liveEvent(it, LiveTvClock.nowMs())
        }
    }

    private fun freshSource(candidate: LiveChannel, rules: SportsWatchRules): LiveChannel? {
        if (candidate.sourceId == SportsAddonSource.SOURCE_ID) {
            val current = repo.matches.value.values.asSequence().flatten()
                .firstOrNull { it.channel.key == candidate.key }?.channel ?: return null
            return current.takeIf(rules::permitsAddon)
        }
        return LiveTvRepository.displayChannels.value.firstOrNull { it.key == candidate.key }?.takeIf(rules::permits)
    }

    fun close() {
        if (closed) return
        closed = true
        openedEventId?.let(repo::closeGame)
        openedEventId = null
        repo.setViewing(false)
        scope.cancel()
    }
}
