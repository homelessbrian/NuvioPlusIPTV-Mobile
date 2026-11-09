package com.nuvio.app.features.livetv.data

import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.model.PlaylistSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * The server's time zone for catch-up, for playlists that don't know it yet (the TV app's way).
 * Tried in turn: the zone the server reports (player_api.php), the gap between the server's clock
 * and the real time (for servers that don't name a zone), the time zone written in the provider's
 * own guide (xmltv.php on the same server), and a guide of this provider's already on this device.
 * The first answer is saved with the playlist, so later replays don't ask again.
 *
 * A lookup runs on its own, so a caller that stops waiting (slow server) doesn't stop it: the zone
 * is still saved when the answer comes. A lookup that failed is tried again after [RETRY_MS].
 */
internal object ServerZones {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = SynchronizedObject()
    private val xtream: XtreamClient by lazy { XtreamClient() }

    /** Lookups in progress, per playlist (shared, so a second replay doesn't ask again). */
    private val lookups = HashMap<String, Deferred<String?>>()
    /** Playlist id -> when its lookup last failed. */
    private val failedAt = HashMap<String, Long>()
    /** Playlist id -> when its time zone was last found (this session), for the background re-check. */
    private val checkedAt = HashMap<String, Long>()

    /** A failed lookup is tried again after this long. */
    private const val RETRY_MS = 2L * 60L * 1000L
    /** A worked-out time zone (fixed offset) is looked up again after this long, in the background. */
    private const val RECHECK_MS = 12L * 60L * 60L * 1000L
    /** How much of the provider's guide is read to find its time zone. */
    const val GUIDE_PROBE_BYTES = 64 * 1024

    private val M3U_XTREAM_LINK = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(\.[a-z0-9]+)?(\?.*)?$""", RegexOption.IGNORE_CASE)

    /** Whether a zone can be looked up for this channel: an Xtream login, or an M3U with Xtream-style links. */
    fun canLookUp(channel: LiveChannel, playlist: PlaylistSource?): Boolean =
        playlist != null && (playlist.isXtream || M3U_XTREAM_LINK.containsMatchIn(channel.url))

    /** An Xtream login whose zone was just read from its own account reply (it loads with every update). */
    fun noteFound(playlistId: String) = synchronized(lock) { checkedAt[playlistId] = LiveTvClock.nowMs() }

    /** The server's time zone for [channel]'s playlist: the saved one, else looked up (null when not found). */
    suspend fun learn(channel: LiveChannel): String? {
        val pl = LiveTvPreferences.currentPlaylists().firstOrNull { it.id == channel.sourceId } ?: return null
        pl.serverTimezone.takeIf { it.isNotBlank() }?.let { return it }
        val failed = synchronized(lock) { failedAt[pl.id] }
        if (failed != null && LiveTvClock.nowMs() - failed < RETRY_MS) return null
        return start(pl, channel.url).await()
    }

    /**
     * A time zone worked out from the server's clock or guide is a fixed offset, which goes wrong
     * when the clocks change: looked up again in the background, at most every [RECHECK_MS].
     * Zones the server names (Europe/Amsterdam) follow the clock changes themselves.
     */
    fun recheck(channel: LiveChannel) {
        scope.launch {
            val pl = LiveTvPreferences.currentPlaylists().firstOrNull { it.id == channel.sourceId } ?: return@launch
            if (!ZoneFinder.isWorkedOut(pl.serverTimezone)) return@launch
            val now = LiveTvClock.nowMs()
            val due = synchronized(lock) {
                val last = checkedAt[pl.id]
                when {
                    last == null -> { checkedAt[pl.id] = now; false } // first replay this session: just found or loaded
                    now - last < RECHECK_MS -> false
                    else -> { checkedAt[pl.id] = now; true }
                }
            }
            if (due) start(pl, channel.url)
        }
    }

    private fun start(pl: PlaylistSource, channelUrl: String): Deferred<String?> = synchronized(lock) {
        lookups[pl.id] ?: scope.async {
            try {
                lookup(pl, channelUrl)
            } finally {
                synchronized(lock) { lookups.remove(pl.id) }
            }
        }.also { lookups[pl.id] = it }
    }

    private suspend fun lookup(pl: PlaylistSource, channelUrl: String): String? {
        // Where to ask: an Xtream login's own server; for an M3U, the server its channel links point to.
        val target: Triple<String, String, String>? = if (pl.isXtream) {
            Triple(pl.xtreamBase(), pl.xtreamUsername.trim(), pl.xtreamPassword.trim())
        } else {
            M3U_XTREAM_LINK.find(channelUrl)?.let { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        }
        var found: Pair<String, String>? = null
        if (target != null) {
            val (host, user, pass) = target
            // 1 and 2: the server's account info (its time zone, else its clock).
            found = attempt { xtream.serverZone(host, user, pass, pl.userAgent) }
            // 3: the provider's own guide, on the same server.
            if (found == null) found = attempt { ZoneFinder.fromGuide(xtream.guideStart(host, user, pass, pl.userAgent, GUIDE_PROBE_BYTES)) }
        }
        // 4: a guide already downloaded for this provider (its file is on this device, so the
        // server can't refuse it): one on the provider's own site, an Xtream panel guide, or one
        // named after the playlist. Other guides (third-party sites) are written in their own zone.
        if (found == null) {
            val links = listOfNotNull(target?.first, pl.url, channelUrl)
            found = attempt {
                LiveTvRepository.providerGuideStarts(pl, links, GUIDE_PROBE_BYTES)
                    .firstNotNullOfOrNull { (name, text) -> ZoneFinder.fromGuide(text)?.let { it.first to "from the guide \"$name\"" } }
            }
        }
        val now = LiveTvClock.nowMs()
        if (found != null) {
            val tz = found.first
            synchronized(lock) { failedAt.remove(pl.id); checkedAt[pl.id] = now }
            LiveTvPreferences.updatePlaylists { list -> list.map { if (it.id == pl.id) it.copy(serverTimezone = tz) else it } }
            return tz
        }
        synchronized(lock) { failedAt[pl.id] = now }
        return null
    }

    private suspend fun <T> attempt(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }
}
