package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object WatchProgressRepository {
    private val _uiState = MutableStateFlow(WatchProgressUiState())
    val uiState: StateFlow<WatchProgressUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var entriesByVideoId: MutableMap<String, WatchProgressEntry> = mutableMapOf()

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true

        val payload = WatchProgressStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) return

        entriesByVideoId = WatchProgressCodec.decodeEntries(payload)
            .associateBy { it.videoId }
            .toMutableMap()
        publish()
    }

    fun upsertPlaybackProgress(
        session: WatchProgressPlaybackSession,
        snapshot: PlayerPlaybackSnapshot,
    ) {
        ensureLoaded()
        upsert(session = session, snapshot = snapshot, persist = true)
    }

    fun flushPlaybackProgress(
        session: WatchProgressPlaybackSession,
        snapshot: PlayerPlaybackSnapshot,
    ) {
        ensureLoaded()
        upsert(session = session, snapshot = snapshot, persist = true)
    }

    fun clearProgress(videoId: String) {
        ensureLoaded()
        if (entriesByVideoId.remove(videoId) != null) {
            publish()
            persist()
        }
    }

    fun progressForVideo(videoId: String): WatchProgressEntry? {
        ensureLoaded()
        return entriesByVideoId[videoId]
    }

    fun resumeEntryForSeries(metaId: String): WatchProgressEntry? {
        ensureLoaded()
        return entriesByVideoId.values.toList().resumeEntryForSeries(metaId)
    }

    fun continueWatching(): List<WatchProgressEntry> {
        ensureLoaded()
        return entriesByVideoId.values.toList().continueWatchingEntries()
    }

    private fun upsert(
        session: WatchProgressPlaybackSession,
        snapshot: PlayerPlaybackSnapshot,
        persist: Boolean,
    ) {
        val positionMs = snapshot.positionMs.coerceAtLeast(0L)
        val durationMs = snapshot.durationMs.coerceAtLeast(0L)
        val isCompleted = isWatchProgressComplete(
            positionMs = positionMs,
            durationMs = durationMs,
            isEnded = snapshot.isEnded,
        )
        if (!isCompleted && !shouldStoreWatchProgress(positionMs = positionMs, durationMs = durationMs)) {
            return
        }

        entriesByVideoId[session.videoId] = WatchProgressEntry(
            contentType = session.contentType,
            parentMetaId = session.parentMetaId,
            parentMetaType = session.parentMetaType,
            videoId = session.videoId,
            title = session.title,
            logo = session.logo,
            poster = session.poster,
            background = session.background,
            seasonNumber = session.seasonNumber,
            episodeNumber = session.episodeNumber,
            episodeTitle = session.episodeTitle,
            episodeThumbnail = session.episodeThumbnail,
            lastPositionMs = if (isCompleted && durationMs > 0L) durationMs else positionMs,
            durationMs = durationMs,
            lastUpdatedEpochMs = WatchProgressClock.nowEpochMs(),
            providerName = session.providerName,
            providerAddonId = session.providerAddonId,
            lastStreamTitle = session.lastStreamTitle,
            lastStreamSubtitle = session.lastStreamSubtitle,
            lastSourceUrl = session.lastSourceUrl,
            isCompleted = isCompleted,
        )
        publish()
        if (persist) persist()
    }

    private fun publish() {
        _uiState.value = WatchProgressUiState(
            entries = entriesByVideoId.values.toList().sortedByDescending { it.lastUpdatedEpochMs },
        )
    }

    private fun persist() {
        WatchProgressStorage.savePayload(
            WatchProgressCodec.encodeEntries(entriesByVideoId.values),
        )
    }
}
