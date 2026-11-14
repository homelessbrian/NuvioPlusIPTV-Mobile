package com.nuvio.app.features.livetv.data

import com.nuvio.app.features.livetv.model.CatchupInfo
import com.nuvio.app.features.livetv.stalker.StalkerGateway
import com.nuvio.app.features.livetv.stalker.StalkerCatalogCache
import com.nuvio.stalker.StalkerConfig
import com.nuvio.stalker.StalkerError
import com.nuvio.stalker.StalkerException
import io.ktor.http.encodeURLParameter
import com.nuvio.app.features.livetv.model.ChannelNameEditor
import com.nuvio.app.features.livetv.model.EpgAssignment
import com.nuvio.app.features.livetv.model.EpgChannelEntry
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.EpgSource
import com.nuvio.app.features.livetv.model.EpgSourceChannels
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.model.LiveTvSettings
import com.nuvio.app.features.livetv.model.PlaylistGroupOrder
import com.nuvio.app.features.livetv.model.PlaylistSource
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class LiveTvStatus(
    val loading: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val loadedOnce: Boolean = false,
    /** A routine update (scheduled check, start-up, after a sync): no "working on it" line while channels are on screen. */
    val silent: Boolean = false,
)

/**
 * Live TV's data: downloads playlists (M3U links and Xtream logins) and guides (XMLTV) on a
 * schedule, builds the channel list, matches each channel to its listings and keeps the guide
 * in [EpgStore]. The behaviour and status fields follow the TV app's LiveTvRepository so a
 * setup synced from a TV behaves the same here.
 */
internal object LiveTvRepository {
    private val prefs = LiveTvPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val xtream = XtreamClient()

    private val _channels = MutableStateFlow<List<LiveChannel>>(emptyList())
    /** Every channel from every enabled playlist, as the playlists have them. */
    val channels: StateFlow<List<LiveChannel>> = _channels.asStateFlow()

    /**
     * Listings around now (a few hours back, half a day ahead), by channel key, title and times
     * only. The full guide is read from [EpgStore] as screens need it.
     */
    private val _programs = MutableStateFlow<Map<String, List<EpgProgram>>>(emptyMap())
    val programs: StateFlow<Map<String, List<EpgProgram>>> = _programs.asStateFlow()

    /** Channel key -> the guide channel picked automatically (shown in Assign EPG). */
    private val _autoMatches = MutableStateFlow<Map<String, EpgAssignment>>(emptyMap())
    val autoMatches: StateFlow<Map<String, EpgAssignment>> = _autoMatches.asStateFlow()

    /** Every loaded guide with its channel list (for Assign EPG). */
    private val _epgSources = MutableStateFlow<List<EpgSourceChannels>>(emptyList())
    val epgSources: StateFlow<List<EpgSourceChannels>> = _epgSources.asStateFlow()
    private val _epgListsLoading = MutableStateFlow(false)
    val epgListsLoading: StateFlow<Boolean> = _epgListsLoading.asStateFlow()

    /** Channel key -> logo from the guide it was matched to. */
    private val _epgLogos = MutableStateFlow<Map<String, String>>(emptyMap())
    val epgLogos: StateFlow<Map<String, String>> = _epgLogos.asStateFlow()

    private val _status = MutableStateFlow(LiveTvStatus())
    val status: StateFlow<LiveTvStatus> = _status.asStateFlow()

    /** Goes up each time the stored guide changes (screens reading from [EpgStore] reload). */
    private val _guideGeneration = MutableStateFlow(0)
    val guideGeneration: StateFlow<Int> = _guideGeneration.asStateFlow()

    /**
     * The channel list with your renames, numbers, group names, the channel name editor and
     * logo choices applied: what every screen shows.
     */
    val displayChannels: StateFlow<List<LiveChannel>> = displayChannelChanges(
        _channels, prefs.userState, prefs.settings, _epgLogos,
        prefs.playlists.map { list -> list.filter { it.logoSource == "epg" }.mapTo(HashSet()) { it.id } }.distinctUntilChanged()
    ) { channels, overrides, cleanup, logos, preferEpg ->
        withPickedLogos(withLogos(named(channels, overrides, cleanup.removals, cleanup.cleanGroups), logos, preferEpg), overrides.channelLogos)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private var initialized = false
    private var firstCheckDone = false
    /** When the last automatic check finished (see [refreshIfStale]). */
    private var lastAutoCheckAt = 0L
    private val embeddedEpgUrls = HashMap<String, List<String>>()
    private var refreshJob: Job? = null

    // ---------------------------------------------------------------- lifecycle

    /** Loads what's saved straight away, then checks for playlist and guide updates. */
    fun ensureLoaded(updateCheckDelayMs: Long = 4_000L) {
        if (initialized) return
        initialized = true
        scope.launch {
            runCatching { mutex.withLock { rebuildLocked() } }
            if (updateCheckDelayMs > 0) delay(updateCheckDelayMs)
            autoCheck()
            firstCheckDone = true
            // Keep checking while the app stays open: the guide runs out after a day or two.
            while (true) {
                delay(STALE_CHECK_MS)
                autoCheck()
                runCatching { withContext(Dispatchers.Default) { EpgStore.compact() } }
            }
        }
    }

    /** Opening Live TV: update anything past its update time. */
    fun refreshIfStale() {
        if (!firstCheckDone || mutex.isLocked) return
        // Just checked (the start-up check, or Live TV opened a moment ago): nothing can be due yet.
        if (LiveTvClock.nowMs() - lastAutoCheckAt < RECHECK_GAP_MS) return
        scope.launch { autoCheck() }
    }

    /** An automatic check for anything past its update time; remembers when it finished. */
    private suspend fun autoCheck() {
        try { runCatching { refreshInternal(forcePlaylists = false, forceEpg = false, routine = true) } }
        finally { lastAutoCheckAt = LiveTvClock.nowMs() }
    }

    fun refreshAll(force: Boolean = true) {
        scope.launch { runCatching { refreshInternal(forcePlaylists = force, forceEpg = force) } }
    }

    fun refreshEpgOnly() {
        scope.launch { runCatching { refreshInternal(forcePlaylists = false, forceEpg = true) } }
    }

    /** After a restore from Google Drive: rebuild from the new sources, downloading what's missing. */
    fun reloadAfterSync(changedPlaylists: Set<String> = emptySet()) {
        scope.launch {
            runCatching { mutex.withLock {
                changedPlaylists.forEach { id ->
                    if (prefs.currentPlaylists().firstOrNull { it.id == id }?.isStalker == true) {
                        StalkerGateway.sourceChanged(id)
                        discardStalkerSnapshots(id)
                        com.nuvio.app.features.livetv.ondemand.OnDemandRepository.sourceChanged(id)
                    }
                }
                rebuildLocked()
            } }
            // Channels already on screen: the update that follows a sync isn't announced.
            runCatching { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = changedPlaylists, routine = _channels.value.isNotEmpty()) }
        }
    }

    // ---------------------------------------------------------------- sources

    @OptIn(ExperimentalUuidApi::class)
    private fun newId(): String = Uuid.random().toString()

    fun addPlaylist(name: String, url: String, userAgent: String = ""): String {
        val id = newId()
        prefs.updatePlaylists { it + PlaylistSource(id = id, name = name.ifBlank { "Playlist" }, url = url.trim(), userAgent = userAgent.trim()) }
        scope.launch { runCatching { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(id)) } }
        return id
    }

    fun addXtream(name: String, server: String, username: String, password: String, importVod: Boolean = false, importLive: Boolean = true): String {
        val id = newId()
        val fromLink = PlaylistSource.credentialsFromLink(server)
        val user = username.trim().ifBlank { fromLink?.first.orEmpty() }
        val pass = password.trim().ifBlank { fromLink?.second.orEmpty() }
        prefs.updatePlaylists {
            it + PlaylistSource(
                id = id, name = name.ifBlank { "Xtream" }, url = "",
                xtreamServer = PlaylistSource.cleanXtreamServer(server),
                xtreamUsername = user, xtreamPassword = pass,
                importVod = importVod, importLive = importLive
            )
        }
        scope.launch { runCatching { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(id)) } }
        return id
    }

    fun addStalker(name: String, config: StalkerConfig, userAgent: String = "", importVod: Boolean = true,
        importLive: Boolean = true, useEmbeddedEpg: Boolean = true): String {
        val id = newId()
        prefs.updatePlaylists { it + PlaylistSource(id = id, name = name.ifBlank { "Stalker" }, url = "",
            providerKind = "stalker", stalker = config, userAgent = userAgent.trim(), importVod = importVod,
            importLive = importLive, useEmbeddedEpg = useEmbeddedEpg) }
        scope.launch { runCatching { refreshInternal(false, false, setOf(id)) } }
        return id
    }

    fun updatePlaylist(input: PlaylistSource) {
        val source = if (input.isXtream) {
            val fromLink = PlaylistSource.credentialsFromLink(input.xtreamServer)
            input.copy(
                xtreamServer = PlaylistSource.cleanXtreamServer(input.xtreamServer),
                xtreamUsername = input.xtreamUsername.trim().ifBlank { fromLink?.first.orEmpty() },
                xtreamPassword = input.xtreamPassword.trim().ifBlank { fromLink?.second.orEmpty() }
            )
        } else input
        val before = prefs.currentPlaylists().firstOrNull { it.id == source.id }
        prefs.updatePlaylists { list -> list.map { if (it.id == source.id) source else it } }
        if (before == null || !before.sameConnection(source)) scope.launch {
            StalkerGateway.sourceChanged(source.id)
            if (before?.isStalker == true || source.isStalker) com.nuvio.app.features.livetv.ondemand.OnDemandRepository.sourceChanged(source.id)
        }
        if (!source.liveEnabled) {
            dropPlaylistNow(source.id)
            if (before != null && !before.sameConnection(source) && (before.isStalker || source.isStalker)) {
                scope.launch { mutex.withLock { discardStalkerSnapshots(source.id) } }
            }
            if (before?.liveEnabled == true) scope.launch { runCatching { mutex.withLock { rebuildLocked() } } }
            return
        }
        val credentialsChanged = before == null || !before.sameConnection(source)
        scope.launch {
            runCatching { mutex.withLock {
                if (credentialsChanged && (before?.isStalker == true || source.isStalker)) discardStalkerSnapshots(source.id)
                rebuildLocked()
            } }
            runCatching { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = if (credentialsChanged) setOf(source.id) else emptySet()) }
        }
    }

    fun updatePlaylistNow(id: String) {
        scope.launch { runCatching { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(id)) } }
    }

    fun removePlaylist(id: String) {
        prefs.updatePlaylists { list -> list.filterNot { it.id == id } }
        prefs.setPlaylistAccount(id, null)
        LiveTvFiles.delete(LiveTvDirs.PLAYLISTS, "$id.m3u")
        LiveTvFiles.delete(LiveTvDirs.PLAYLISTS, groupOrderFile(id))
        embeddedEpgUrls.remove(id)
        discardStalkerSnapshots(id)
        scope.launch { StalkerGateway.invalidateSource(id) }
        dropPlaylistNow(id)
        scope.launch { runCatching { mutex.withLock { rebuildLocked() } } }
    }

    private fun dropPlaylistNow(id: String) {
        builtSegments = builtSegments - id
        val remaining = _channels.value.filterNot { it.sourceId == id }
        if (remaining.size == _channels.value.size) return
        val keys = remaining.mapTo(HashSet()) { it.key }
        _channels.value = remaining
        _programs.value = _programs.value.filterKeys { it in keys }
    }

    fun setPlaylistEnabled(id: String, enabled: Boolean) {
        prefs.updatePlaylists { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
        if (!enabled) dropPlaylistNow(id)
        scope.launch {
            runCatching { mutex.withLock { rebuildLocked() } }
            runCatching { refreshInternal(forcePlaylists = false, forceEpg = false) }
        }
    }

    fun movePlaylist(id: String, delta: Int) {
        prefs.updatePlaylists { list ->
            val m = list.toMutableList()
            val i = m.indexOfFirst { it.id == id }
            if (i < 0) return@updatePlaylists list
            val item = m.removeAt(i)
            m.add((i + delta).coerceIn(0, m.size), item)
            m
        }
        scope.launch { runCatching { mutex.withLock { rebuildLocked() } } }
    }

    fun addEpg(name: String, url: String): String {
        val id = newId()
        prefs.updateEpgSources { it + EpgSource(id = id, name = name.ifBlank { "EPG" }, url = url.trim()) }
        scope.launch { runCatching { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(id)) } }
        return id
    }

    fun updateEpg(source: EpgSource) {
        prefs.updateEpgSources { list -> list.map { if (it.id == source.id) source else it } }
        scope.launch { runCatching { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(source.id)) } }
    }

    fun removeEpg(id: String) {
        prefs.updateEpgSources { list -> list.filterNot { it.id == id } }
        LiveTvFiles.delete(LiveTvDirs.EPG, "$id.xml")
        scope.launch {
            runCatching { mutex.withLock { rebuildLocked() } }
            runCatching { refreshInternal(forcePlaylists = false, forceEpg = true) }
        }
    }

    fun setEpgEnabled(id: String, enabled: Boolean) {
        prefs.updateEpgSources { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
        scope.launch { runCatching { refreshInternal(forcePlaylists = false, forceEpg = true) } }
    }

    fun setPlaylistLogoSource(id: String, logoSource: String) {
        prefs.updatePlaylists { list -> list.map { if (it.id == id) it.copy(logoSource = logoSource) else it } }
    }

    /** Catch-up time zone picked for one playlist ("" = Automatic): only replay links change, nothing reloads. */
    fun setPlaylistCatchupZone(id: String, zone: String) {
        prefs.updatePlaylists { list -> list.map { if (it.id == id) it.copy(catchupTimezone = zone) else it } }
    }

    /** A playlist's built-in guide time shift: the guide is matched and stored again with it. */
    fun setPlaylistGuideShift(id: String, minutes: Int) {
        val before = prefs.currentPlaylists().firstOrNull { it.id == id }?.guideShiftMinutes ?: return
        if (before == minutes) return
        prefs.updatePlaylists { list -> list.map { if (it.id == id) it.copy(guideShiftMinutes = minutes) else it } }
        rematchEpg()
    }

    /** A playlist's catch-up time shift: only the next catch-up link changes, nothing reloads. */
    fun setPlaylistCatchupShift(id: String, minutes: Int) {
        prefs.updatePlaylists { list -> list.map { if (it.id == id) it.copy(catchupShiftMinutes = minutes) else it } }
    }

    /** A guide source's time shift: the guide is matched and stored again with it. */
    fun setEpgShift(id: String, minutes: Int) {
        val before = prefs.currentEpgSources().firstOrNull { it.id == id }?.shiftMinutes ?: return
        if (before == minutes) return
        prefs.updateEpgSources { list -> list.map { if (it.id == id) it.copy(shiftMinutes = minutes) else it } }
        rematchEpg()
    }

    // ---------------------------------------------------------------- refresh

    /** True while a routine update runs (see [refreshInternal], [rebuildLocked]): its progress isn't announced. */
    @kotlin.concurrent.Volatile private var routineRun = false

    private fun setStatus(loading: Boolean, message: String?, error: String? = null) {
        _status.value = _status.value.copy(loading = loading, message = message, error = error, silent = routineRun)
    }

    private suspend fun refreshInternal(forcePlaylists: Boolean, forceEpg: Boolean, forceIds: Set<String> = emptySet(), routine: Boolean = false) {
        mutex.withLock {
            // A scheduled check works without the progress line; an update you asked for (or a new playlist) shows it.
            routineRun = routine
            try {
                refreshBody(forcePlaylists, forceEpg, forceIds)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _status.value = LiveTvStatus(loading = false, loadedOnce = true, error = e.message ?: "Update failed")
            } finally { routineRun = false }
            runCatching { sweepLeftoverFiles() }
        }
    }

    private suspend fun refreshBody(forcePlaylists: Boolean, forceEpg: Boolean, forceIds: Set<String>) {
        val settings = prefs.currentSettings()
        val now = LiveTvClock.nowMs()
        val playlists = prefs.currentPlaylists().filter { it.liveEnabled }
        var changed = false
        var firstError: String? = null
        // Playlists downloaded for the first time (just added): the channel list is still made
        // from every playlist, but the message only names these.
        val newlyAdded = ArrayList<String>()

        for (pl in playlists) {
            val file = playlistFile(pl.id)
            val exists = LiveTvFiles.exists(LiveTvDirs.PLAYLISTS, file)
            val stale = !exists || now - LiveTvFiles.lastModified(LiveTvDirs.PLAYLISTS, file) > settings.playlistRefreshHours.coerceAtLeast(1) * HOUR
            if (!(forcePlaylists || stale || pl.id in forceIds)) continue
            setStatus(loading = true, message = "Updating playlist \"${pl.name}\"…")
            val result = runCatching {
                if (pl.isStalker) downloadStalker(pl, file)
                else if (pl.isXtream) downloadXtream(pl, file)
                else if (!pl.isM3u) throw StalkerException(StalkerError.UNSUPPORTED)
                else downloadM3u(pl, file)
            }
            if (pl.isM3u) runCatching { refreshM3uAccount(pl) }
            val err = result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it else it.message ?: "Download failed" }
            prefs.updatePlaylists { list ->
                list.map { if (it.id == pl.id) it.copy(lastUpdatedMs = if (err == null) now else it.lastUpdatedMs, lastError = err) else it }
            }
            if (err != null && firstError == null) firstError = "${pl.name}: $err"
            if (!exists && LiveTvFiles.exists(LiveTvDirs.PLAYLISTS, file)) newlyAdded += pl.name
            if (result.getOrNull() == true || (!exists && LiveTvFiles.exists(LiveTvDirs.PLAYLISTS, file))) changed = true
        }

        if (changed || _channels.value.isEmpty()) {
            val adding = addingMessage(newlyAdded)
            setStatus(loading = true, message = adding ?: "Reading channels…")
            buildChannels(adding)
        }

        val targets = collectEpgTargets()
        for (target in targets) {
            val file = epgFile(target.fileId)
            val exists = LiveTvFiles.exists(LiveTvDirs.EPG, file)
            val stale = !exists || now - LiveTvFiles.lastModified(LiveTvDirs.EPG, file) > settings.epgRefreshHours.coerceAtLeast(1) * HOUR
            val forcedHere = target.sourceId in forceIds || target.playlistId in forceIds
            if (!(forceEpg || stale || forcedHere)) continue
            setStatus(loading = true, message = "Downloading guide \"${target.name}\"…")
            val result = runCatching {
                if (target.stalkerSource != null) {
                    val client = StalkerGateway.client(target.stalkerSource)
                    // Request the full configured display horizon; the portal decides its available archive/future coverage.
                    val hours = (settings.epgPastHours.coerceAtLeast(1) + settings.epgFutureDays.coerceAtLeast(1) * 24)
                    val identity = StalkerGateway.sourceIdentity(target.stalkerSource)
                    LiveTvFiles.writeTextStream(LiveTvDirs.EPG, file,
                        publish = { commit -> prefs.withCurrentPlaylist(target.stalkerSource, commit) }) { emit ->
                        client.forEachProgramme(hours) { programme -> emit(StalkerCatalogCache.guideLine(programme, identity)) }
                    }
                } else LiveTvFiles.download(target.url, LiveTvDirs.EPG, file, target.userAgent) { bytes ->
                    setStatus(loading = true, message = "Downloading guide \"${target.name}\" (${sizeLabel(bytes)})…")
                }
            }
            if (result.getOrNull() == true) changed = true
            val err = result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it else it.message ?: "Download failed" }
            if (target.sourceId != null) {
                prefs.updateEpgSources { list ->
                    list.map { if (it.id == target.sourceId) it.copy(lastUpdatedMs = if (err == null) now else it.lastUpdatedMs, lastError = err) else it }
                }
            }
            if (err != null && firstError == null) firstError = "${target.name}: $err"
        }

        if (changed || forceEpg || forceIds.isNotEmpty() || EpgStore.fingerprint() != guideFingerprint(settings, targets)) {
            setStatus(loading = true, message = "Matching guide data…")
            buildPrograms(settings, targets)
        }
        _status.value = LiveTvStatus(loading = false, loadedOnce = true, error = firstError)
    }

    /**
     * Reads the saved channels and guide (nothing downloaded). The stored guide goes on screen
     * straight away, even when something it depends on changed since (an update cut short, a
     * changed playlist or guide file): it stays there while the guide is matched again, so the
     * guide is never empty at start-up just because it's being brought up to date.
     */
    private suspend fun rebuildLocked() {
        // Channels already on screen (a rebuild after a change): reading the guide again isn't announced.
        val hadChannels = _channels.value.isNotEmpty()
        routineRun = hadChannels
        try {
            buildChannels()
            val settings = prefs.currentSettings()
            val targets = collectEpgTargets()
            val stored = runCatching { EpgStore.fingerprint() }.getOrNull()
            if (_channels.value.isNotEmpty() && stored == guideFingerprint(settings, targets)) {
                loadGuideWindow()
                _autoMatches.value = withContext(Dispatchers.Default) { EpgStore.autoMatches() }
            } else if (_channels.value.isNotEmpty() && targets.any { LiveTvFiles.exists(LiveTvDirs.EPG, epgFile(it.fileId)) }) {
                if (stored != null) {
                    // The guide as it was last stored, shown as it is while it's brought up to date.
                    runCatching {
                        loadGuideWindow()
                        _autoMatches.value = withContext(Dispatchers.Default) { EpgStore.autoMatches() }
                    }
                    routineRun = true
                }
                setStatus(loading = true, message = "Matching guide data…")
                buildPrograms(settings, targets)
            }
        } finally { routineRun = false }
        _status.value = _status.value.copy(loading = false, loadedOnce = true, silent = false)
    }

    private suspend fun sweepLeftoverFiles() = withContext(Dispatchers.Default) {
        val now = LiveTvClock.nowMs()
        val oldEnough = now - 14L * 24 * HOUR
        val guideIds = collectEpgTargets().mapTo(HashSet()) { it.fileId }
        prefs.currentEpgSources().forEach { guideIds += it.id }
        val stalkerIds = prefs.currentPlaylists().filter { it.isStalker }.map { it.id }
        LiveTvFiles.list(LiveTvDirs.EPG).filter { name -> stalkerIds.any { name.startsWith("auto_stalker_${it}_") && name.contains("_archive_") } }
            .forEach { guideIds += it.substringBefore(".xml") }
        val playlistIds = prefs.currentPlaylists().mapTo(HashSet()) { it.id }
        fun sweep(dir: String, idOf: (String) -> String, keep: Set<String>) {
            LiveTvFiles.list(dir).forEach { name ->
                if (LiveTvFiles.lastModified(dir, name) > oldEnough) return@forEach
                if (idOf(name) in keep) return@forEach
                LiveTvFiles.delete(dir, name)
            }
        }
        sweep(LiveTvDirs.EPG, { it.substringBefore(".xml").substringBefore(".tmp") }, guideIds)
        sweep(LiveTvDirs.PLAYLISTS, { it.substringBefore(".m3u").substringBefore(".groups").substringBefore(".tmp") }, playlistIds)
    }

    // ---------------------------------------------------------------- downloads

    private fun discardStalkerSnapshots(id: String) {
        LiveTvFiles.delete(LiveTvDirs.PLAYLISTS, playlistFile(id))
        LiveTvFiles.list(LiveTvDirs.EPG).filter { it.startsWith("auto_stalker_${id}_") || it.startsWith("stalker_${id}.") || it.startsWith("stalker_${id}_archive_") }
            .forEach { LiveTvFiles.delete(LiveTvDirs.EPG, it) }
    }

    private fun stalkerGuideId(source: PlaylistSource): String = "auto_stalker_${source.id}_${StalkerGateway.sourceIdentity(source)}"

    private suspend fun downloadStalker(pl: PlaylistSource, file: String): Boolean {
        val client = StalkerGateway.client(pl)
        val groups = client.liveGroups()
        val identity = StalkerGateway.sourceIdentity(pl)
        val channels = client.liveChannels().map { it.copy(stream = it.stream.copy(sourceIdentity = identity)) }
        // Both paginated calls finish before replacing the working snapshot.
        return LiveTvFiles.writeTextStream(LiveTvDirs.PLAYLISTS, file,
            publish = { commit -> prefs.withCurrentPlaylist(pl, commit) }) { emit ->
            StalkerCatalogCache.channelLines(groups, channels).forEach(emit)
        }
    }

    private fun playlistFile(id: String) = "$id.m3u"
    /** An Xtream login's group names in the provider's order (see [PlaylistGroupOrder]). */
    private fun groupOrderFile(id: String) = "$id.groups"

    /** The providers' own group order, for the group list. */
    private fun loadGroupOrder(playlists: List<PlaylistSource>) {
        PlaylistGroupOrder.ranks = runCatching {
            val multi = playlists.size > 1
            val ranks = HashMap<String, Int>()
            for (pl in playlists) {
                if (pl.isStalker) {
                    runCatching { StalkerCatalogCache.channels(pl.id).first }.getOrDefault(emptyList()).forEachIndexed { index, group ->
                        ranks["${pl.id}::stalker::${group.id.encodeURLParameter()}"] = index
                    }
                    continue
                }
                val text = LiveTvFiles.readSmall(LiveTvDirs.PLAYLISTS, groupOrderFile(pl.id)) ?: continue
                text.lines().filter { it.isNotBlank() }.forEachIndexed { i, name ->
                    if ((if (multi) "${pl.id}::$name" else name) !in ranks) ranks[if (multi) "${pl.id}::$name" else name] = i
                }
            }
            ranks
        }.getOrDefault(emptyMap())
    }
    private fun epgFile(id: String) = "$id.xml"

    /** An Xtream login: the API's channel list written as an M3U; get.php when the API won't answer. */
    private suspend fun downloadXtream(pl: PlaylistSource, file: String): Boolean {
        val viaApi = runCatching { xtream.livePlaylistM3u(pl) { count -> setStatus(loading = true, message = "Reading \"${pl.name}\": $count channels…") } }
        val playlist = viaApi.getOrNull()
        if (playlist != null) {
            playlist.account.account?.let { prefs.setPlaylistAccount(pl.id, it) }
            if (playlist.groupOrder.isNotEmpty()) runCatching { LiveTvFiles.writeSmall(LiveTvDirs.PLAYLISTS, groupOrderFile(pl.id), playlist.groupOrder.joinToString("\n")) }
            playlist.account.serverTimezone?.let { tz ->
                if (tz != pl.serverTimezone) prefs.updatePlaylists { l -> l.map { if (it.id == pl.id) it.copy(serverTimezone = tz) else it } }
                ServerZones.noteFound(pl.id)
            }
            return LiveTvFiles.writeText(LiveTvDirs.PLAYLISTS, file, playlist.m3u)
        }
        val e = viaApi.exceptionOrNull()
        if (e is XtreamLoginError) throw e
        if (e is kotlinx.coroutines.CancellationException) throw e
        // The panel's API failed: the plain playlist link usually still works.
        return LiveTvFiles.download(pl.resolvedUrl(), LiveTvDirs.PLAYLISTS, file, pl.userAgent) { bytes ->
            setStatus(loading = true, message = "Downloading playlist \"${pl.name}\" (${sizeLabel(bytes)})…")
        }
    }

    /**
     * Downloads an M3U link. With no user agent set for it: if the link answers the app's
     * default (browser-style) user agent with a video, web page or "forbidden" instead of
     * channels, it's asked again as a media player (VLC), like other IPTV apps. The one that
     * worked is remembered for this playlist (and used for its channels and guide), and
     * checked again the same way whenever it stops working.
     */
    private suspend fun downloadM3u(pl: PlaylistSource, file: String): Boolean {
        val url = pl.resolvedUrl()
        val progress: (Long) -> Unit = { bytes -> setStatus(loading = true, message = "Downloading playlist \"${pl.name}\" (${sizeLabel(bytes)})…") }
        if (pl.userAgent.isNotBlank()) return LiveTvFiles.download(url, LiveTvDirs.PLAYLISTS, file, pl.userAgent, onProgress = progress)
        val first = pl.fallbackUserAgent.ifBlank { XtreamClient.DEFAULT_UA }
        val second = if (first == XtreamClient.DEFAULT_UA) PLAYER_UA else XtreamClient.DEFAULT_UA
        val firstProblem = try {
            return LiveTvFiles.download(url, LiveTvDirs.PLAYLISTS, file, first, checkPlaylist = true, onProgress = progress)
        } catch (e: NotAPlaylistException) {
            e.what
        }
        val changed = try {
            LiveTvFiles.download(url, LiveTvDirs.PLAYLISTS, file, second, checkPlaylist = true, onProgress = progress)
        } catch (e: NotAPlaylistException) {
            error("The link sent $firstProblem instead of a channel list. If the provider asks for a specific user agent, enter it in this playlist's settings.")
        }
        val remember = if (second == XtreamClient.DEFAULT_UA) "" else second
        prefs.updatePlaylists { list -> list.map { if (it.id == pl.id) it.copy(fallbackUserAgent = remember) else it } }
        // Its channels' user agent changed too, so they're made again even if the list is the same.
        return changed || remember != pl.fallbackUserAgent
    }

    /** "Adding channels from …" for playlists just added, or null when none were. */
    private fun addingMessage(names: List<String>): String? = when (names.size) {
        0 -> null
        1 -> "Adding channels from \"${names[0]}\"…"
        else -> "Adding channels from ${names.size} new playlists…"
    }

    /** M3U links from Xtream providers (get.php with a login): the account's expiry and status. */
    private suspend fun refreshM3uAccount(pl: PlaylistSource) {
        val api = xtream.m3uAccountUrl(pl.resolvedUrl()) ?: return
        val info = runCatching { xtream.accountInfo(api, pl.effectiveUserAgent) }.getOrNull() ?: return
        info.account?.let { prefs.setPlaylistAccount(pl.id, it) }
    }

    private fun sizeLabel(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "${bytes / (1024L * 1024 * 1024)} GB"
        bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)} MB"
        else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
    }

    // ---------------------------------------------------------------- channels

    /**
     * A channel renamed in the playlist (by the provider or a playlist editor) gets a new key.
     * This moves favorites, hidden channels, groups you made, renames, guide choices and so on
     * over to it (see [RenamedChannels]). [before]: each channel's stream link before the
     * playlists were read again, null when there's no earlier list. Without one, any channel can
     * be the match, so that's only done once per device (for channels renamed before this existed).
     */
    private fun carryOverRenamedChannels(before: Map<String, String>?, now: List<LiveChannel>) {
        runCatching {
            if (now.isEmpty()) return
            val firstTime = !prefs.renamedChannelsChecked()
            if (before == null && !firstTime) return
            val current = now.mapTo(HashSet(now.size * 2)) { it.key }
            val lost = prefs.channelKeysInUse().filter { it !in current }
            val moves = RenamedChannels.match(lost, now, before.orEmpty(), onlyNew = !firstTime)
            if (moves.isNotEmpty()) prefs.moveChannelKeys(moves)
            if (firstTime) prefs.setRenamedChannelsChecked()
        }
    }

    /** Each playlist's part of what the current channel list was read from (see [buildChannels]). */
    @kotlin.concurrent.Volatile private var builtSegments: Map<String, String> = emptyMap()

    /** One playlist's file and settings that its channels depend on: the same value means its channels are unchanged. */
    private fun playlistSegment(pl: PlaylistSource): String {
        val f = playlistFile(pl.id)
        return "${pl.id}:${pl.name.hashCode()}:${pl.effectiveUserAgent.hashCode()}:${pl.streamFormat}:" +
            "${LiveTvFiles.size(LiveTvDirs.PLAYLISTS, f)}:${LiveTvFiles.lastModified(LiveTvDirs.PLAYLISTS, f)}"
    }

    /**
     * Makes one channel list from every playlist that's on (numbers carry on from one playlist
     * to the next). Only playlists that changed are read again: the others' channels are taken
     * from the list already there, so turning a playlist off reads nothing and turning one on
     * reads just that one. [fixedMessage]: shown for the whole read instead of naming each
     * playlist, so adding a playlist doesn't look like the others are loading again.
     */
    private suspend fun buildChannels(fixedMessage: String? = null) = withContext(Dispatchers.Default) {
        val before = _channels.value.takeIf { it.isNotEmpty() }?.associate { it.key to it.url }
        val reuseSegments = builtSegments
        val oldBySource = _channels.value.groupBy { it.sourceId }
        val playlists = prefs.currentPlaylists().filter { it.liveEnabled }
        val multi = playlists.size > 1
        val out = ArrayList<LiveChannel>()
        val usedKeys = HashSet<String>()
        // Provider portals are always read from their own saved copy (no file date to go by).
        val segments = HashMap<String, String>()
        playlists.forEach { if (!it.isStalker) segments[it.id] = playlistSegment(it) }
        val toRead = playlists.count { it.isStalker || reuseSegments[it.id] != segments[it.id] || oldBySource[it.id] == null }
        var read = 0
        var counter = 1
        for (pl in playlists) {
            val file = playlistFile(pl.id)
            if (!LiveTvFiles.exists(LiveTvDirs.PLAYLISTS, file)) continue
            val old = oldBySource[pl.id]
            if (!pl.isStalker && old != null && reuseSegments[pl.id] == segments[pl.id]) {
                // Unchanged: same channels; only numbers (they carry on from the playlists
                // before it) and group ids (prefixed when there's more than one playlist) follow.
                for (c in old) {
                    val number = if (c.autoNumber) counter else c.number
                    counter = maxOf(counter, number) + 1
                    usedKeys += c.key
                    val groupId = if (c.stalker != null) c.groupId else if (multi) "${pl.id}::${c.group}" else c.group
                    out += if (number == c.number && groupId == c.groupId) c else c.copy(number = number, groupId = groupId)
                }
                continue
            }
            read++
            if (fixedMessage == null) setStatus(loading = true, message = "Reading channels from \"${pl.name}\"" + (if (toRead > 1) " ($read of $toRead)" else "") + "…")
            if (pl.isStalker) {
                val (groups, raw) = runCatching { StalkerCatalogCache.channels(pl.id) }.getOrNull() ?: continue
                val titles = groups.associate { it.id to it.name }
                for (c in raw) {
                    val key = StalkerCatalogCache.channelKey(pl.id, c.id)
                    if (!usedKeys.add(key)) continue
                    val group = titles[c.groupId] ?: "Uncategorized"
                    val given = c.number?.toIntOrNull()?.takeIf { it > 0 }
                    val number = given ?: counter
                    counter = maxOf(counter, number) + 1
                    out += LiveChannel(key, pl.id, pl.name, c.name, c.epgId, c.name, c.logo,
                        groupId = "${pl.id}::stalker::${c.groupId.orEmpty().encodeURLParameter()}", group = group,
                        number = number, url = StalkerGateway.locator(pl.id, c.stream),
                        headers = pl.userAgent.takeIf { it.isNotBlank() }?.let { mapOf("User-Agent" to it) }.orEmpty(),
                        catchup = if (c.archiveEnabled && c.archiveDays > 0) CatchupInfo("stalker", null, c.archiveDays) else null,
                        stalker = c.stream, autoNumber = given == null)
                }
                if (raw.size != pl.channelCount) prefs.updatePlaylists { list -> list.map { if (it.id == pl.id) it.copy(channelCount = raw.size) else it } }
                continue
            }
            if (!pl.isM3u && !pl.isXtream) continue
            val parsed = runCatching {
                // Movies and episodes are left out while reading (see the check below).
                LiveTvFiles.readChunks(LiveTvDirs.PLAYLISTS, file) { chunks -> M3uParser.parse(linesOf(chunks)) { url -> isVodEntry(url) } }
            }.getOrNull() ?: continue
            embeddedEpgUrls[pl.id] = parsed.epgUrls
            // The playlist's own User-Agent: one shared copy for every channel with no headers of its own.
            val agent = pl.effectiveUserAgent
            val agentOnly = if (agent.isNotBlank()) mapOf("User-Agent" to agent) else emptyMap()
            var count = 0
            for (e in parsed.entries) {
                // Many providers' M3U links also list every movie and episode: not TV channels.
                if (isVodEntry(e.url)) continue
                count++
                val groupTitle = e.group?.ifBlank { null } ?: "Uncategorized"
                val baseKey = "${pl.id}|${e.tvgId ?: ""}|${e.name}"
                var key = baseKey
                var n = 2
                while (!usedKeys.add(key)) { key = "$baseKey#${n++}" }
                val number = e.chno ?: counter
                counter = maxOf(counter, number) + 1
                val headers = if (agent.isNotBlank() && e.headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    if (e.headers.isEmpty()) agentOnly else e.headers + ("User-Agent" to agent)
                } else e.headers
                out += LiveChannel(
                    key = key,
                    sourceId = pl.id,
                    sourceName = pl.name,
                    name = e.name,
                    tvgId = e.tvgId,
                    tvgName = e.tvgName,
                    logo = e.logo,
                    groupId = if (multi) "${pl.id}::$groupTitle" else groupTitle,
                    group = groupTitle,
                    number = number,
                    url = e.url,
                    headers = headers,
                    catchup = e.catchup,
                    drm = e.drm,
                    tvgShiftMinutes = e.tvgShiftMinutes,
                    autoNumber = e.chno == null
                )
            }
            if (count != pl.channelCount) {
                prefs.updatePlaylists { list -> list.map { if (it.id == pl.id) it.copy(channelCount = count) else it } }
            }
        }
        loadGroupOrder(playlists)
        builtSegments = segments
        // A playlist switched off while the channels were being read: leave its channels out
        // (it was already taken out of the guide, and this would have put it back).
        val stillOn = prefs.currentPlaylists().filter { it.liveEnabled }.mapTo(HashSet()) { it.id }
        val kept = if (out.all { it.sourceId in stillOn }) out else out.filter { it.sourceId in stillOn }
        _channels.value = kept
        carryOverRenamedChannels(before, kept)
    }

    /** The chunks of a file as lines (a line may span two chunks). */
    internal fun linesOf(chunks: Sequence<String>): Sequence<String> = sequence {
        var carry = StringBuilder()
        for (chunk in chunks) {
            var start = 0
            while (true) {
                val nl = chunk.indexOf('\n', start)
                if (nl < 0) { carry.append(chunk, start, chunk.length); break }
                if (carry.isEmpty()) yield(chunk.substring(start, nl))
                else { carry.append(chunk, start, nl); yield(carry.toString()); carry = StringBuilder() }
                start = nl + 1
            }
        }
        if (carry.isNotEmpty()) yield(carry.toString())
    }

    // ---------------------------------------------------------------- guide

    private data class EpgTarget(
        val fileId: String,
        val sourceId: String?,
        val name: String,
        val url: String,
        val userAgent: String,
        val playlistId: String? = null,
        /** How far this guide's listings are moved (its own time shift), in ms. */
        val shiftMs: Long = 0L,
        val stalkerSource: PlaylistSource? = null,
    )

    /** Each guide file's time shift (ms), as last collected. */
    @kotlin.concurrent.Volatile private var targetShifts: Map<String, Long> = emptyMap()

    /**
     * How far a channel's stored listings were moved: the time shift of the guide it's matched to
     * (or given in Assign EPG; its playlist's own guide when it has none) plus the playlist's
     * tvg-shift for it. Catch-up takes this back off: the archive goes by the guide's own times.
     */
    fun guideShiftMs(channel: LiveChannel): Long {
        val a = prefs.currentUserState().epgOverrides[channel.key]?.takeUnless { it.isNone } ?: _autoMatches.value[channel.key]
        val named = a?.let { m -> prefs.currentEpgSources().firstOrNull { it.id == m.sourceId } }
        val source = named?.let { it.shiftMinutes * MINUTE }
            ?: a?.let { targetShifts[it.sourceId] }
            ?: prefs.currentPlaylists().firstOrNull { it.id == channel.sourceId }?.let { it.guideShiftMinutes * MINUTE }
            ?: 0L
        return source + channel.tvgShiftMinutes * MINUTE
    }

    private fun List<EpgProgram>.shiftedBy(ms: Long): List<EpgProgram> =
        if (ms == 0L || isEmpty()) this else map { it.copy(startMs = it.startMs + ms, stopMs = it.stopMs + ms) }

    /**
     * The first [maxBytes] of each guide on this device that comes from [pl]'s provider (one on
     * the provider's own site, an Xtream panel guide, or one named after the playlist), with its
     * name: for working out the provider's time zone.
     */
    internal suspend fun providerGuideStarts(pl: PlaylistSource, links: List<String>, maxBytes: Int): List<Pair<String, String>> =
        withContext(Dispatchers.Default) {
            fun site(link: String): String? = hostOf(link)?.split('.')?.filter { it.isNotEmpty() }?.takeLast(2)?.joinToString(".")?.takeIf { it.contains('.') }
            val sites = links.mapNotNull { site(it) }.toSet()
            val name = normalize(pl.name)
            collectEpgTargets().filter { t ->
                t.playlistId == pl.id || site(t.url) in sites || t.url.contains("xmltv.php", ignoreCase = true) ||
                    (name.length >= 3 && normalize(t.name).contains(name))
            }.mapNotNull { t ->
                val file = epgFile(t.fileId)
                if (!LiveTvFiles.exists(LiveTvDirs.EPG, file)) return@mapNotNull null
                val text = runCatching {
                    LiveTvFiles.readChunks(LiveTvDirs.EPG, file) { chunks ->
                        val sb = StringBuilder()
                        for (c in chunks) { sb.append(c); if (sb.length >= maxBytes) break }
                        sb.toString()
                    }
                }.getOrNull() ?: return@mapNotNull null
                t.name to text
            }
        }

    private fun hostOf(u: String): String? = runCatching { Url(PlaylistSource.tidyLink(u)).host.lowercase() }.getOrNull()

    /** Named guides first (they win on conflicts), then the guides playlists point to. */
    private fun collectEpgTargets(): List<EpgTarget> {
        val targets = mutableListOf<EpgTarget>()
        val allPlaylists = prefs.currentPlaylists()
        prefs.currentEpgSources().filter { it.enabled && it.url.isNotBlank() }.forEach { src ->
            val host = hostOf(src.url)
            val ua = allPlaylists.firstOrNull { pl ->
                pl.effectiveUserAgent.isNotBlank() && host != null && hostOf(if (pl.isXtream) pl.xtreamBase() else pl.url) == host
            }?.effectiveUserAgent.orEmpty()
            targets += EpgTarget(src.id, src.id, src.name, src.url, ua, shiftMs = src.shiftMinutes * MINUTE)
        }
        for (pl in allPlaylists.filter { it.liveEnabled && it.useEmbeddedEpg }) {
            if (pl.isStalker) {
                if (pl.stalker == null) continue
                targets += EpgTarget(stalkerGuideId(pl), null, "${pl.name} (built-in)", "", pl.userAgent,
                    playlistId = pl.id, shiftMs = pl.guideShiftMinutes * MINUTE, stalkerSource = pl)
                continue
            }
            val urls = mutableListOf<String>()
            pl.xtreamEpgUrl()?.let { urls += it }
            urls += embeddedEpgUrls[pl.id].orEmpty()
            urls.distinct().forEach { url ->
                if (targets.none { it.url == url }) {
                    targets += EpgTarget(
                        "auto_" + stableHash(url), null, "${pl.name} (built-in)", url, pl.effectiveUserAgent,
                        playlistId = pl.id, shiftMs = pl.guideShiftMinutes * MINUTE,
                    )
                }
            }
        }
        targetShifts = targets.associate { it.fileId to it.shiftMs }
        return targets
    }

    private fun targetStamp(t: EpgTarget): String =
        "${t.fileId}:${LiveTvFiles.lastModified(LiveTvDirs.EPG, epgFile(t.fileId))}:${LiveTvFiles.size(LiveTvDirs.EPG, epgFile(t.fileId))}" +
            (if (t.shiftMs != 0L) ":s=${t.shiftMs / MINUTE}" else "")

    private fun guideFingerprint(settings: LiveTvSettings, targets: List<EpgTarget>): String {
        val channels = _channels.value
        var h = channels.size.toLong()
        for (c in channels) h = h * 31 + c.key.hashCode() * 7L + (c.tvgId?.hashCode() ?: 0) * 3L + (c.tvgName?.hashCode() ?: 0) + c.name.hashCode() + c.tvgShiftMinutes
        val overrides = prefs.currentUserState().epgOverrides
        return "v$GUIDE_VERSION|$h|${overrides.hashCode()}|${settings.epgPastHours}|${settings.epgFutureDays}|" + targets.joinToString(",") { targetStamp(it) }
    }

    private fun defaultWindow(now: Long = LiveTvClock.nowMs()) = (now - 3 * HOUR) to (now + 12 * HOUR)

    private suspend fun loadGuideWindow() = withContext(Dispatchers.Default) {
        val (from, to) = defaultWindow()
        _programs.value = EpgStore.range(from, to, light = true)
        _guideGeneration.value++
    }

    private suspend fun buildPrograms(settings: LiveTvSettings, targets: List<EpgTarget>) = withContext(Dispatchers.Default) {
        val all = _channels.value
        if (all.isEmpty()) {
            _programs.value = emptyMap()
            return@withContext
        }
        val hidden = prefs.currentHiddenEverywhere()
        val channels = all.filter { c -> !hidden.hides(c.key, c.groupId) }
        val now = LiveTvClock.nowMs()
        val windowStart = now - settings.epgPastHours.coerceAtLeast(1) * HOUR
        val windowEnd = now + settings.epgFutureDays.coerceAtLeast(1) * 24 * HOUR
        val overrides = prefs.currentUserState().epgOverrides.filterKeys { key -> channels.any { it.key == key } }

        val byTvgId = HashMap<String, MutableList<LiveChannel>>()
        val byName = HashMap<String, MutableList<LiveChannel>>()
        channels.forEach { c ->
            if (c.key in overrides) return@forEach
            c.tvgId?.lowercase()?.takeIf { it.isNotBlank() }?.let { byTvgId.getOrPut(it) { ArrayList() }.add(c) }
            normalize(c.tvgName ?: "").takeIf { it.isNotEmpty() }?.let { byName.getOrPut(it) { ArrayList() }.add(c) }
            normalize(c.name).takeIf { it.isNotEmpty() }?.let { byName.getOrPut(it) { ArrayList() }.add(c) }
            c.tvgId?.let { idName(it) }?.takeIf { it.length >= 3 }?.let { k ->
                val l = byName.getOrPut(k) { ArrayList() }
                if (c !in l) l.add(c)
            }
        }

        class Candidate(val score: Int, val sourceId: String, val xmltvId: String, val programs: List<EpgProgram>, val icon: String?)
        val best = HashMap<String, Candidate>()
        val sourceLists = ArrayList<EpgSourceChannels>()

        for ((order, target) in targets.withIndex()) {
            val file = epgFile(target.fileId)
            if (!LiveTvFiles.exists(LiveTvDirs.EPG, file) && target.stalkerSource == null) continue
            setStatus(loading = true, message = "Matching guide \"${target.name}\"" + (if (targets.size > 1) " (${order + 1} of ${targets.size})" else "") + "…")
            val src = target.fileId
            val pinnedHere = overrides.filterValues { it.sourceId == src }.entries.groupBy({ it.value.xmltvId }, { it.key })
            val idHits = HashMap<String, MutableSet<LiveChannel>>()
            val nameHits = HashMap<String, MutableSet<LiveChannel>>()
            fun matchOne(id: String, names: List<String>) {
                byTvgId[id.lowercase()]?.let { idHits.getOrPut(id) { LinkedHashSet() }.addAll(it) }
                (names.map { normalize(it) } + normalize(id) + idName(id)).distinct().forEach { k ->
                    if (k.isNotEmpty()) byName[k]?.let { nameHits.getOrPut(id) { LinkedHashSet() }.addAll(it) }
                }
            }
            if (target.stalkerSource != null) {
                val portal = target.stalkerSource
                val raw = runCatching { StalkerCatalogCache.channels(portal.id).second }.getOrDefault(emptyList())
                val native = runCatching {
                    val base = StalkerCatalogCache.guide(file, windowStart - target.shiftMs, windowEnd - target.shiftMs)
                    val archives = LiveTvFiles.list(LiveTvDirs.EPG).filter { it.startsWith("${target.fileId}_archive_") && it.endsWith(".xml") }
                    if (archives.isEmpty()) base else base.toMutableMap().apply {
                        archives.forEach { snapshot ->
                            StalkerCatalogCache.guide(snapshot, windowStart - target.shiftMs, windowEnd - target.shiftMs).forEach { (id, list) ->
                                this[id] = (this[id].orEmpty() + list).associateBy { it.startMs to it.stopMs }.values.toList()
                            }
                        }
                    }
                }.getOrDefault(emptyMap())
                val used = HashSet<String>()
                val directById = channels.filter { it.sourceId == portal.id && it.stalker != null && it.key !in overrides }.groupBy { it.stalker!!.providerId }
                val channelsByKey = channels.associateBy { it.key }
                raw.forEach { remote ->
                    val list = native[remote.id]?.let { cleanListings(it).shiftedBy(target.shiftMs) } ?: return@forEach
                    if (list.isEmpty()) return@forEach
                    val direct = directById[remote.id].orEmpty()
                    val pinned = pinnedHere[remote.id].orEmpty().mapNotNull { channelsByKey[it] }
                    (direct + pinned).forEach { channel ->
                        val score = (if (channel.key in overrides) 8 else 0) + (if (!isPlaceholderListing(list)) 2 else 0) + 1
                        if (best[channel.key]?.score?.let { it >= score } != true) {
                            best[channel.key] = Candidate(score, src, remote.id, list, remote.logo)
                            used += remote.id
                        }
                    }
                }
                sourceLists += EpgSourceChannels(src, target.name, target.name,
                    raw.map { EpgChannelEntry(it.id, listOf(it.name), it.logo) }, used)
                continue
            }
            val collected = HashMap<String, MutableList<EpgProgram>>()
            var listed: Map<String, XmltvChannel> = emptyMap()
            val result = runCatching {
                LiveTvFiles.readChunks(LiveTvDirs.EPG, file) { chunks ->
                    XmltvParser.parse(
                        chunks, windowStart - target.shiftMs, windowEnd - target.shiftMs,
                        keepUnlisted = { xid ->
                            matchOne(xid, emptyList())
                            xid in pinnedHere || idHits.containsKey(xid) || nameHits.containsKey(xid)
                        },
                        // This guide's own time shift, applied as it's stored.
                        onPrograms = { xid, list -> collected.getOrPut(xid) { ArrayList() }.addAll(list.shiftedBy(target.shiftMs)) },
                        selectChannels = { chans ->
                            listed = chans
                            if (chans.isEmpty()) null
                            else {
                                chans.values.forEach { matchOne(it.id, it.displayNames) }
                                (idHits.keys + nameHits.keys + pinnedHere.keys).toSet()
                            }
                        }
                    )
                }
            }.getOrNull() ?: continue
            val entries = listed.values.map { EpgChannelEntry(it.id, it.displayNames, it.icon) }
                .ifEmpty { result.programChannelIds.map { EpgChannelEntry(it, emptyList(), null) } }
            val used = HashSet<String>()
            fun offer(xid: String, chans: Collection<LiveChannel>, idMatch: Boolean, pinned: Boolean) {
                val list = collected[xid]?.let { cleanListings(it) } ?: return
                if (list.isEmpty()) return
                val real = !isPlaceholderListing(list)
                val score = (if (pinned) 8 else 0) + (if (real) 2 else 0) + (if (idMatch) 1 else 0)
                val icon = listed[xid]?.icon
                chans.forEach { c ->
                    val cur = best[c.key]
                    if (cur == null || score > cur.score) {
                        best[c.key] = Candidate(score, src, xid, list, icon)
                        used += xid
                    }
                }
            }
            pinnedHere.forEach { (xid, keys) -> offer(xid, keys.mapNotNull { k -> channels.firstOrNull { it.key == k } }, idMatch = true, pinned = true) }
            idHits.forEach { (xid, chans) -> offer(xid, chans, idMatch = true, pinned = false) }
            nameHits.forEach { (xid, chans) -> offer(xid, chans, idMatch = false, pinned = false) }
            sourceLists += EpgSourceChannels(src, target.name, hostOf(target.url) ?: target.name, entries, used)
        }

        val programs = HashMap<String, List<EpgProgram>>(best.size)
        val auto = HashMap<String, EpgAssignment>(best.size)
        val logos = HashMap<String, String>()
        // Channels with a tvg-shift in the playlist: their listings moved by that too.
        val tvgShifts = channels.filter { it.tvgShiftMinutes != 0 }.associate { it.key to it.tvgShiftMinutes * MINUTE }
        best.forEach { (key, c) ->
            programs[key] = c.programs.shiftedBy(tvgShifts[key] ?: 0L)
            if (key !in overrides) auto[key] = EpgAssignment(c.sourceId, c.xmltvId)
            c.icon?.takeIf { it.isNotBlank() }?.let { logos[key] = it }
        }
        setStatus(loading = true, message = "Storing the guide…")
        runCatching { EpgStore.replaceAll(guideFingerprint(settings, targets), programs, auto) }
        _autoMatches.value = auto
        _epgLogos.value = logos
        // Every guide's channel list is only kept while Assign EPG or the logo picker is open.
        if (listsWanted > 0) _epgSources.value = sourceLists
        val (from, to) = defaultWindow(now)
        val copies = GuideWindowCopies(from, to)
        val window = HashMap<String, List<EpgProgram>>(best.size)
        best.forEach { (key, c) ->
            val list = copies.forGuide(c.sourceId, c.xmltvId, c.programs)
            if (list.isNotEmpty()) window[key] = tvgShifts[key]?.let { list.shiftedBy(it) } ?: list
        }
        _programs.value = window
        _guideGeneration.value++
    }

    /** Listings cleaned the TV app's way: sorted, overlaps resolved, placeholders over real shows dropped. */
    private fun cleanListings(list: List<EpgProgram>): List<EpgProgram> {
        if (list.isEmpty()) return list
        val sorted = list.filter { it.stopMs > it.startMs }.sortedBy { it.startMs }
        val out = ArrayList<EpgProgram>(sorted.size)
        var lastStop = Long.MIN_VALUE
        for (p in sorted) {
            if (p.startMs < lastStop) {
                // Overlaps the show before: a placeholder gives way; a real show cuts the one before.
                if (isPlaceholderTitle(p.title)) continue
                val prev = out.lastOrNull()
                if (prev != null && prev.stopMs > p.startMs) {
                    if (isPlaceholderTitle(prev.title)) out.removeAt(out.lastIndex) else out[out.lastIndex] = prev.copy(stopMs = p.startMs)
                }
            }
            out += p
            lastStop = p.stopMs
        }
        return out
    }

    private fun isPlaceholderListing(list: List<EpgProgram>): Boolean = list.all { isPlaceholderTitle(it.title) }

    /** How many pickers (Assign EPG, Change logo) are open and want every guide's channel list. */
    @kotlin.concurrent.Volatile private var listsWanted = 0

    /**
     * Assign EPG or the logo picker closed: once none is open, the guides' channel lists (which
     * can hold tens of thousands of channels) are let go; they're read again the next time.
     */
    fun releaseSourceLists() {
        listsWanted = (listsWanted - 1).coerceAtLeast(0)
        if (listsWanted == 0) _epgSources.value = emptyList()
    }

    /**
     * Assign EPG (or the logo picker) opened: make sure every guide's channel list is there.
     * Each call is matched by one [releaseSourceLists] when the picker closes.
     */
    fun ensureSourceLists() {
        listsWanted++
        if (_epgSources.value.isNotEmpty() || _epgListsLoading.value) return
        _epgListsLoading.value = true
        scope.launch {
            runCatching {
                val targets = collectEpgTargets()
                val lists = ArrayList<EpgSourceChannels>()
                for (t in targets) {
                    val file = epgFile(t.fileId)
                    if (!LiveTvFiles.exists(LiveTvDirs.EPG, file)) continue
                    val listed = withContext(Dispatchers.Default) {
                        if (t.stalkerSource != null) StalkerCatalogCache.channels(t.stalkerSource.id).second.associate {
                            it.id to XmltvChannel(it.id, listOf(it.name), it.logo)
                        } else LiveTvFiles.readChunks(LiveTvDirs.EPG, file) { chunks ->
                            var found: Map<String, XmltvChannel> = emptyMap()
                            // Only the channel list is needed: stop at the first show.
                            XmltvParser.parse(chunks, 0L, 1L, selectChannels = { chans -> found = chans; throw ChannelListRead() })
                            found
                        }
                    }.orEmpty()
                    val used = _autoMatches.value.values.filter { it.sourceId == t.fileId }.mapTo(HashSet()) { it.xmltvId }
                    lists += EpgSourceChannels(t.fileId, t.name, hostOf(t.url) ?: t.name, listed.values.map { EpgChannelEntry(it.id, it.displayNames, it.icon) }, used)
                }
                // Only if a picker is still open (and an update didn't put fresher lists there meanwhile).
                if (listsWanted > 0 && _epgSources.value.isEmpty()) _epgSources.value = lists
            }
            _epgListsLoading.value = false
        }
    }

    private class ChannelListRead : RuntimeException()

    /** Assign EPG: these channels' guides were changed; match again. */
    fun applyEpgChanges(changes: Map<String, EpgAssignment?>) {
        changes.forEach { (key, a) -> prefs.setEpgOverride(key, a) }
        rematchEpg()
    }

    fun rematchEpg() {
        scope.launch {
            mutex.withLock {
                runCatching {
                    setStatus(loading = true, message = "Matching guide data…")
                    buildPrograms(prefs.currentSettings(), collectEpgTargets())
                }
                _status.value = _status.value.copy(loading = false, loadedOnce = true)
            }
        }
    }

    // ---------------------------------------------------------------- guide reads

    /** What's on now and next for each channel, from the hours kept in memory. */
    fun nowNext(keys: Collection<String>, nowMs: Long = LiveTvClock.nowMs()): Map<String, NowNext> {
        val progs = _programs.value
        val out = HashMap<String, NowNext>()
        keys.forEach { k ->
            val l = progs[k] ?: return@forEach
            val cur = l.firstOrNull { nowMs >= it.startMs && nowMs < it.stopMs }
            val nxt = l.firstOrNull { it.startMs >= (cur?.stopMs ?: nowMs) }
            out[k] = NowNext(cur, nxt)
        }
        return out
    }

    /** Listings for these channels across [fromMs, toMs), read from the stored guide. */
    suspend fun programsIn(keys: Collection<String>, fromMs: Long, toMs: Long): Map<String, List<EpgProgram>> =
        withContext(Dispatchers.Default) { EpgStore.range(fromMs, toMs, light = true, channels = keys) }

    /** One show's full details. */
    suspend fun programDetails(channelKey: String, startMs: Long): EpgProgram? =
        withContext(Dispatchers.Default) { EpgStore.program(channelKey, startMs) }
            ?: _programs.value[channelKey]?.firstOrNull { it.startMs == startMs }

    /** A retained UI row must still belong to the currently configured portal identity. */
    fun isCurrentStalkerChannel(channel: LiveChannel): Boolean {
        val ref = channel.stalker ?: return false
        val source = prefs.currentPlaylists().firstOrNull { it.id == channel.sourceId && it.liveEnabled && it.isStalker } ?: return false
        return ref.sourceIdentity != null && ref.sourceIdentity == StalkerGateway.sourceIdentity(source)
    }

    /** A channel's whole schedule for the days the guide keeps. */
    suspend fun channelSchedule(channelKey: String, fromMs: Long, toMs: Long): List<EpgProgram> {
        ensureArchives(listOf(channelKey), fromMs, toMs)
        return withContext(Dispatchers.Default) { EpgStore.channelRange(channelKey, fromMs, toMs) }
    }

    /** Some portals expose historical listings only through a channel/day archive API. */
    private suspend fun ensureArchives(keys: Collection<String>, fromMs: Long, toMs: Long) {
        val now = LiveTvClock.nowMs()
        if (fromMs >= now || fromMs >= toMs) return
        val wanted = keys.toHashSet()
        val selected = _channels.value.filter { it.key in wanted && it.stalker != null && it.catchup != null }
        if (selected.isEmpty()) return
        mutex.withLock {
            for (channel in selected) {
                val source = prefs.currentPlaylists().firstOrNull { it.id == channel.sourceId && it.liveEnabled && it.useEmbeddedEpg } ?: continue
                val config = source.stalker ?: continue
                if (channel.stalker?.sourceIdentity != StalkerGateway.sourceIdentity(source)) continue
                val override = prefs.currentUserState().epgOverrides[channel.key]
                if (override != null && (override.isNone || override.sourceId != stalkerGuideId(source))) continue
                val assigned = _autoMatches.value[channel.key]
                if (override == null && assigned != null && assigned.sourceId != stalkerGuideId(source)) continue
                val shift = guideShiftMs(channel)
                val start = maxOf(fromMs - shift, now - channel.catchup!!.days * 24L * HOUR)
                val end = minOf(toMs - shift, now)
                if (start >= end) continue
                val first = CivilTime.at(start, config.timezone)
                val last = CivilTime.at(end - 1, config.timezone)
                val firstDay = CivilTime.daysFromCivil(first.year, first.month, first.day)
                val lastDay = CivilTime.daysFromCivil(last.year, last.month, last.day)
                for (day in firstDay..lastDay) {
                    val date = CivilTime.atUtc(day * 24 * HOUR).let { "${it.year.toString().padStart(4, '0')}-${it.month.toString().padStart(2, '0')}-${it.day.toString().padStart(2, '0')}" }
                    val file = "${stalkerGuideId(source)}_archive_${channel.stalker!!.providerId.encodeURLParameter()}_$date.xml"
                    // Archive listings may be corrected by the provider, including on closed dates.
                    val fresh = LiveTvFiles.exists(LiveTvDirs.EPG, file) && now - LiveTvFiles.lastModified(LiveTvDirs.EPG, file) < HOUR
                    if (fresh) continue
                    try {
                        val identity = StalkerGateway.sourceIdentity(source)
                        val rows = StalkerGateway.client(source).archiveGuide(channel.stalker.providerId, date)
                        LiveTvFiles.writeTextStream(LiveTvDirs.EPG, file,
                            publish = { commit -> prefs.withCurrentPlaylist(source, commit) }) { emit -> rows.forEach { emit(StalkerCatalogCache.guideLine(it, identity)) } }
                        val incoming = rows.filter { it.channelId == channel.stalker.providerId }.map { p ->
                            EpgProgram(p.startMillis + shift, p.stopMillis + shift, p.title, p.description, p.category,
                                stalkerProgramId = p.archiveProgramId, stalkerArchiveAvailable = p.archiveAvailable,
                                stalkerSourceIdentity = identity.takeIf { p.archiveProgramId != null })
                        }
                        withContext(Dispatchers.Default) {
                            val previous = EpgStore.channelRange(channel.key, 0L, Long.MAX_VALUE)
                            val merged = cleanListings((previous + incoming).associateBy { it.startMs to it.stopMs }.values.toList())
                            prefs.withCurrentPlaylist(source) {
                                EpgStore.replaceChannels(EpgStore.fingerprint().orEmpty(), setOf(channel.key), mapOf(channel.key to merged),
                                    _autoMatches.value[channel.key]?.let { mapOf(channel.key to it) }.orEmpty())
                            }
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        // The existing guide remains usable if the portal archive is unavailable.
                    }
                }
            }
        }
    }

    /** Moves the in-memory window along (called now and then while Live TV is open). */
    fun refreshWindow() {
        scope.launch { runCatching { loadGuideWindow() } }
    }

    // ---------------------------------------------------------------- display names and logos

    private fun named(channels: List<LiveChannel>, user: ChannelDisplayOverrides, nameRemovals: String, cleanGroups: Boolean = false): List<LiveChannel> {
        val terms = ChannelNameEditor.terms(nameRemovals)
        if (user.channelNames.isEmpty() && user.channelNumbers.isEmpty() && user.groupNames.isEmpty() && terms.isEmpty()) return channels
        val spaced = if (terms.isEmpty()) emptyMap() else channels.groupBy { it.sourceId }.mapValues { (_, list) -> ChannelNameEditor.spacePrefixes(list.map { it.name }, terms) }
        val currentKeys = channels.mapTo(HashSet()) { it.key }
        val namesById = stableIndex(user.channelNames.filterKeys { it !in currentKeys })
        val numbersById = stableIndex(user.channelNumbers.filterKeys { it !in currentKeys })
        // Group names get the same cleaning when that's switched on. Worked out once per
        // distinct group name; a group you renamed yourself keeps your name.
        val cleanedGroups: Map<String, String> =
            if (cleanGroups && terms.isNotEmpty()) channels.mapTo(HashSet()) { it.group }.associateWith { ChannelNameEditor.clean(it, terms) }
            else emptyMap()
        return channels.map { c ->
            val id = stableId(c.key)
            val cleaned = if (terms.isEmpty()) null else ChannelNameEditor.clean(c.name, terms, spaced[c.sourceId].orEmpty()).takeIf { it != c.name }
            c.copy(
                name = user.channelNames[c.key] ?: id?.let { namesById[it] } ?: cleaned ?: c.name,
                number = user.channelNumbers[c.key] ?: id?.let { numbersById[it] } ?: c.number,
                group = user.groupNames[c.groupId] ?: cleanedGroups[c.group]?.ifBlank { null } ?: c.group
            )
        }
    }

    private fun withLogos(channels: List<LiveChannel>, epgLogos: Map<String, String>, preferEpg: Set<String>): List<LiveChannel> {
        if (epgLogos.isEmpty()) return channels
        return channels.map { c ->
            val fromGuide = epgLogos[c.key]
            val logo = if (c.sourceId in preferEpg) fromGuide ?: c.logo else c.logo?.takeIf { it.isNotBlank() } ?: fromGuide
            if (logo == c.logo) c else c.copy(logo = logo)
        }
    }

    private fun withPickedLogos(channels: List<LiveChannel>, picked: Map<String, String>): List<LiveChannel> {
        if (picked.isEmpty()) return channels
        val current = channels.mapTo(HashSet()) { it.key }
        val byId = stableIndex(picked.filterKeys { it !in current })
        return channels.map { c ->
            val logo = picked[c.key] ?: stableId(c.key)?.let { byId[it] }
            if (logo == null || logo == c.logo) c else c.copy(logo = logo)
        }
    }

    /** "playlist|tvg-id" from a channel key, or null when the channel has no tvg-id. */
    private fun stableId(key: String): String? {
        val parts = key.split('|', limit = 3)
        if (parts.size < 3 || parts[1].isBlank()) return null
        return parts[0] + "|" + parts[1]
    }

    private fun <T> stableIndex(byKey: Map<String, T>): Map<String, T> {
        val out = HashMap<String, T>()
        byKey.forEach { (k, v) -> stableId(k)?.let { if (it !in out) out[it] = v } }
        return out
    }

    // ---------------------------------------------------------------- helpers

    private fun stableHash(s: String): String {
        // FNV-1a, 64-bit: a stable file name for a guide link (no hashing library in common code).
        var h = -3750763034362895579L
        for (ch in s) {
            h = h xor ch.code.toLong()
            h *= 1099511628211L
        }
        return h.toULong().toString(16).padStart(16, '0')
    }

    private const val HOUR = 60L * 60L * 1000L
    private const val MINUTE = 60_000L
    private const val STALE_CHECK_MS = 30L * 60L * 1000L
    /** Opening Live TV doesn't check again this soon after the last automatic check. */
    private const val RECHECK_GAP_MS = 5L * 60L * 1000L
    private const val GUIDE_VERSION = 1
    /** Tried for M3U links that don't send channels to the default user agent (see [downloadM3u]). */
    private const val PLAYER_UA = "VLC/3.0.20 LibVLC/3.0.20"

    private val PLACEHOLDER_TITLES = Regex(
        """(programming|program|programme|no information|no info|no program information|no programme information|""" +
            """to be announced|tba|tbd|n/?a|off air|no data|no epg|not available|information not available|""" +
            """regular programming|scheduled programming|paid programming|coming soon|no program guide available)\.?"""
    )
    private val VOD_PATH = Regex("""/(movie|movies|series|vod)/""", RegexOption.IGNORE_CASE)
    private val VOD_EXT = Regex("""\.(mp4|mkv|avi|mov|wmv|m4v|webm|flv)(\?|$)""", RegexOption.IGNORE_CASE)
    private val qualityTokens = Regex("""\b(fhd|uhd|hd|sd|4k|8k|hevc|h265|h264|1080p|720p|backup|raw)\b""")
    private val prefixToken = Regex("""^[a-z]{2,3}\s*[:|]\s*""")
    private val bracketed = Regex("""\[.*?]|\(.*?\)""")
    private val countryEnd = Regex("""(\.(com|co|net|tv))?\.[a-z]{2}$""", RegexOption.IGNORE_CASE)

    /** A movie or episode in an M3U (by its link), not a live channel. */
    fun isVodEntry(url: String): Boolean = VOD_PATH.containsMatchIn(url) || VOD_EXT.containsMatchIn(url)

    /** "Programming", "No information", "To Be Announced"… (no real show). */
    fun isPlaceholderTitle(title: String): Boolean = PLACEHOLDER_TITLES.matches(title.trim().lowercase())

    /** A guide or tvg-id as a name: "GloboSP.br" -> "globosp". */
    internal fun idName(id: String): String = normalize(id.trim().replace(countryEnd, ""))

    internal fun normalize(name: String): String {
        var n = name.lowercase().trim()
        n = n.replace(prefixToken, "")
        n = n.replace(bracketed, " ")
        n = n.replace(qualityTokens, " ")
        return n.filter { it.isLetterOrDigit() }
    }
}
