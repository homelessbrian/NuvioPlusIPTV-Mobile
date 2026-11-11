package com.nuvio.app.features.livetv.ondemand

import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.model.LiveTvSettings
import com.nuvio.app.features.livetv.model.LiveUserState
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.livetv.stalker.StalkerGateway
import com.nuvio.app.features.livetv.player.LivePlayRequest
import com.nuvio.stalker.StalkerStreamRef
import com.nuvio.stalker.StalkerException
import com.nuvio.stalker.StalkerError
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamProxyHeaders
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Builds what the app's player (and the watched list) needs for an On Demand title. */
internal object OnDemandPlayback {
    const val PROVIDER_ID = "livetv-ondemand"

    /** Adult titles that have been played (their provider ids): never saved to Continue watching. */
    private val neverSaved = MutableStateFlow<Set<String>>(emptySet())
    private data class PortalPlayback(val sourceId: String, val ref: StalkerStreamRef, val videoId: String)
    private val portalLock = SynchronizedObject()
    private val portalPlaying = mutableMapOf<String, PortalPlayback>()
    private val portalRetried = mutableSetOf<String>()

    /** Shared by details, Continue watching, automatic next episode and the in-player picker. */
    suspend fun resolveStreamForPlayback(stream: StreamItem, videoId: String?): StreamItem {
        val ref = stream.stalkerStream ?: run {
            if (stream.url?.let(StalkerGateway::isLocator) == true) throw StalkerException(StalkerError.LINK_UNAVAILABLE)
            return stream
        }
        val sourceId = stream.stalkerSourceId?.takeIf { it.isNotBlank() } ?: throw StalkerException(StalkerError.STALE_SESSION)
        val resolved = StalkerGateway.resolve(sourceId, ref)
        synchronized(portalLock) {
            portalPlaying.clear(); portalRetried.clear()
            portalPlaying[resolved.url] = PortalPlayback(sourceId, ref, videoId ?: "$sourceId:${ref.providerId}:${ref.seriesSelector.orEmpty()}")
        }
        return stream.copy(url = resolved.url, streamType = resolved.streamType ?: streamType(resolved.url), stalkerMediaPolicy = resolved.mediaPolicy,
            behaviorHints = stream.behaviorHints.copy(proxyHeaders = StreamProxyHeaders(request = resolved.headers)))
    }

    fun resolutionError(failure: Throwable): String = if (com.nuvio.app.features.livetv.stalker.StalkerPlaybackSupport.isRestriction(failure.message))
        failure.message.orEmpty() else "The provider could not open this title. Check the source and VPN settings."

    /** A create_link result can expire; renew once before showing the normal player error. */
    suspend fun fallbackFor(url: String): LivePlayRequest? {
        val context = synchronized(portalLock) {
            val current = portalPlaying[url] ?: return@synchronized null
            current.takeIf { portalRetried.add(it.videoId) }
        } ?: return null
        val stream = StalkerGateway.resolve(context.sourceId, context.ref)
        synchronized(portalLock) { portalPlaying[stream.url] = context }
        return LivePlayRequest(stream.url, stream.headers, stream.streamType ?: streamType(stream.url), stream.mediaPolicy)
    }

    private fun streamType(url: String): String? = when {
        url.substringBefore('?').endsWith(".m3u8", true) -> "hls"
        url.substringBefore('?').endsWith(".mpd", true) -> "dash"
        else -> null
    }

    private fun baseId(item: VodItem) = "$PROVIDER_ID:${item.uid}"

    fun markNeverSaved(item: VodItem) = neverSaved.update { it + baseId(item) }

    /** True for an adult title's movie or episode, which Continue watching and Trakt/Simkl never get. */
    fun neverSaves(videoId: String?): Boolean {
        val v = videoId ?: return false
        if (!v.startsWith("$PROVIDER_ID:")) return false
        return neverSaved.value.any { v == it || v.startsWith("$it:") }
    }

    /** The id a title is known by in the watched list: its TMDB match, else the provider's uid. */
    fun metaId(item: VodItem, match: VodMatch?): String =
        match?.tmdbId?.takeIf { it.isNotBlank() }?.let { "tmdb:$it" } ?: "$PROVIDER_ID:${item.uid}"

    fun isWatched(item: VodItem, match: VodMatch?, episode: VodEpisode? = null): Boolean =
        WatchedRepository.isWatched(id = metaId(item, match), type = item.kind.key, season = episode?.season, episode = episode?.episode)

    fun toggleWatched(item: VodItem, match: VodMatch?, episode: VodEpisode? = null, now: Long) {
        WatchedRepository.toggleWatched(
            WatchedItem(
                id = metaId(item, match),
                type = item.kind.key,
                name = match?.title ?: item.displayTitle,
                poster = match?.poster?.takeIf { it.isNotBlank() } ?: item.icon,
                releaseInfo = item.year?.toString(),
                season = episode?.season,
                episode = episode?.episode,
                videoId = videoId(item, episode),
                markedAtEpochMs = now,
            )
        )
    }

    fun videoId(item: VodItem, episode: VodEpisode?): String =
        if (episode == null) "$PROVIDER_ID:${item.uid}" else "$PROVIDER_ID:${item.uid}:${episode.season}:${episode.episode}"

    /**
     * The player launch for a movie, or for one [episode] of a series. Null when the title has no link.
     * An [adult] title plays under its provider id only (not its TMDB match) and is never saved.
     */
    suspend fun launch(item: VodItem, episode: VodEpisode?, match: VodMatch?, profileId: Int, adult: Boolean = false): PlayerLaunch? {
        if (adult) return launch(item, episode, null, profileId, adult = false)?.also { markNeverSaved(item) }?.copy(initialPositionMs = 0L, initialProgressFraction = null)
        val ref = episode?.stalker ?: if (episode == null) item.stalker else null
        val portalStream = ref?.let { StalkerGateway.resolve(item.playlistId, it) }
        val url = portalStream?.url ?: (if (episode != null) episode.url else OnDemandRepository.movieUrl(item)) ?: return null
        if (StalkerGateway.isLocator(url)) return null
        val title = match?.title ?: item.displayTitle
        val videoId = videoId(item, episode)
        if (ref != null) synchronized(portalLock) {
            portalPlaying.clear(); portalRetried.clear()
            portalPlaying[url] = PortalPlayback(item.playlistId, ref, videoId)
        }
        val resume = WatchProgressRepository.progressForVideo(videoId)?.takeIf { it.isResumable }
        val poster = match?.poster?.takeIf { it.isNotBlank() } ?: item.icon
        return PlayerLaunch(
            profileId = profileId,
            title = title,
            sourceUrl = url,
            sourceHeaders = portalStream?.headers ?: OnDemandRepository.headersFor(item),
            stalkerMediaPolicy = portalStream?.mediaPolicy,
            streamType = portalStream?.streamType ?: streamType(url),
            poster = poster,
            background = poster,
            seasonNumber = episode?.season,
            episodeNumber = episode?.episode,
            episodeTitle = episode?.title,
            episodeThumbnail = episode?.image,
            streamTitle = if (episode != null) "S${episode.season} E${episode.episode} · ${episode.title}" else title,
            streamSubtitle = OnDemandRepository.playlist(item.playlistId)?.name,
            providerName = OnDemandRepository.playlist(item.playlistId)?.name ?: "On Demand",
            providerAddonId = PROVIDER_ID,
            contentType = item.kind.key,
            videoId = videoId,
            parentMetaId = metaId(item, match),
            parentMetaType = item.kind.key,
            initialPositionMs = resume?.lastPositionMs?.takeIf { it > 0L } ?: 0L,
            initialProgressFraction = resume?.progressFraction?.takeIf { it > 0f },
            sourceFilename = if (portalStream != null) null else url.substringBefore('?').substringAfterLast('/'),
        )
    }
}

/** How On Demand's groups are shown: your order, your names, hidden ones left out, and each group's sort. */
internal object OnDemandGroups {
    /** [categories] as the page lists them for this profile. */
    fun visible(categories: List<VodCategory>, user: LiveUserState): List<VodCategory> {
        val order = user.vodGroupOrder.withIndex().associate { (i, uid) -> uid to i }
        return categories
            .filter { it.uid !in user.vodHiddenCategories }
            .map { c -> user.vodGroupNames[c.uid]?.takeIf { it.isNotBlank() }?.let { c.copy(name = it) } ?: c }
            .withIndex()
            .sortedWith(compareBy<IndexedValue<VodCategory>>({ order[it.value.uid] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value }
    }

    /** The sort for a group (or for "all" with [uid] null): yours for that group, else the default sort. */
    fun sortFor(uid: String?, user: LiveUserState, settings: LiveTvSettings): VodSort =
        uid?.let { VodSort.parse(user.vodSorts[it]) } ?: VodSort.parse(settings.vodDefaultSort) ?: VodSort.DEFAULT

    fun hide(uid: String) = LiveTvPreferences.updateVodHiddenCategories(show = emptySet(), hide = setOf(uid))
    fun show(uid: String) = LiveTvPreferences.updateVodHiddenCategories(show = setOf(uid), hide = emptySet())
}
