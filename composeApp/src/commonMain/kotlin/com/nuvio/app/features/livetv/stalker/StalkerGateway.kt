package com.nuvio.app.features.livetv.stalker

import com.nuvio.app.features.livetv.data.CivilTime
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.data.LiveTvKeys
import com.nuvio.app.features.livetv.data.ZoneFinder
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.model.PlaylistSource
import com.nuvio.app.features.profiles.ProfilePinCrypto
import com.nuvio.stalker.*
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.atomicfu.atomic

/** One in-memory session per configured source and network route. Never stores temporary links. */
internal object StalkerGateway {
    private data class Session(val config: StalkerConfig, val route: String, val client: StalkerClient) {
        val active = atomic(true)
    }
    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, Session>()

    suspend fun client(source: PlaylistSource): StalkerClient {
        ensureCurrent(source)
        val config = source.stalker?.takeIf { source.isStalker }
            ?: throw StalkerException(StalkerError.INVALID_CONFIG)
        val route = stalkerRouteIdentity()
        return mutex.withLock {
            ensureCurrent(source)
            sessions[source.id]?.let { old ->
                if (old.config == config && old.route == route) return@withLock old.client
                sessions.remove(source.id)
                if (sessions.values.none { it === old }) { old.active.value = false; old.client.invalidate() }
            }
            sessions.values.firstOrNull { it.config == config && it.route == route && it.active.value }?.let { shared ->
                sessions[source.id] = shared
                return@withLock shared.client
            }
            StalkerClient(config, object : StalkerStreamingGuideTransport {
                override suspend fun get(url: String, headers: Map<String, String>): StalkerHttpResponse {
                    ensureIdentityCurrent(config)
                    if (stalkerRouteIdentity() != route) throw StalkerException(StalkerError.STALE_SESSION)
                    return stalkerHttpGet(url, headers, route).also { ensureIdentityCurrent(config) }
                }

                override suspend fun getGuide(url: String, headers: Map<String, String>, onRow: (String?, StalkerGuideRow) -> Unit): StalkerGuideHttpResponse {
                    ensureIdentityCurrent(config)
                    if (stalkerRouteIdentity() != route) throw StalkerException(StalkerError.STALE_SESSION)
                    return stalkerHttpGuide(url, headers, route, onRow).also { ensureIdentityCurrent(config) }
                }
            }, LiveTvClock::nowMs, ::parsePortalTime).also {
                sessions[source.id] = Session(config, route, it)
            }
        }
    }

    /** Call before publishing changed/removed source data. A retired session cannot return links. */
    suspend fun invalidateSource(sourceId: String) = mutex.withLock {
        sessions.remove(sourceId)?.let { removed ->
            if (sessions.values.none { it === removed }) { removed.active.value = false; removed.client.invalidate() }
        }
        Unit
    }

    suspend fun sourceChanged(sourceId: String) = invalidateSource(sourceId)

    fun ensureCurrent(source: PlaylistSource) {
        val latest = LiveTvPreferences.currentPlaylists().firstOrNull { it.id == source.id && it.enabled && it.isStalker }
        if (latest?.stalker != source.stalker || latest == null) throw StalkerException(StalkerError.STALE_SESSION)
    }

    private fun ensureIdentityCurrent(config: StalkerConfig) {
        if (LiveTvPreferences.currentPlaylists().none { it.enabled && it.isStalker && it.stalker == config })
            throw StalkerException(StalkerError.STALE_SESSION)
    }

    fun sourceIdentity(source: PlaylistSource): String = source.stalker?.let {
        ProfilePinCrypto.sha256Hex(it.toJson().toString())
    } ?: throw StalkerException(StalkerError.INVALID_CONFIG)

    /** Resolve before MIME inference and the player's normal VPN gate; suitable for every player. */
    suspend fun resolve(sourceId: String, ref: StalkerStreamRef): StalkerPlaybackStream {
        val source = LiveTvPreferences.currentPlaylists().firstOrNull { it.id == sourceId && it.enabled && it.isStalker }
            ?: throw StalkerException(StalkerError.STALE_SESSION)
        val config = source.stalker ?: throw StalkerException(StalkerError.INVALID_CONFIG)
        checkPermission(source, ref)
        if (ref.sourceIdentity == null || ref.sourceIdentity != sourceIdentity(source)) throw StalkerException(StalkerError.STALE_SESSION)
        val client = client(source)
        val route = stalkerRouteIdentity()
        val stream = client.resolve(ref)
        val authorization = client.mediaAuthorization()
        val session = mutex.withLock { sessions[sourceId]?.takeIf { it.client === client } }
            ?: throw StalkerException(StalkerError.STALE_SESSION)
        val latest = LiveTvPreferences.currentPlaylists().firstOrNull { it.id == sourceId && it.enabled && it.isStalker }
        if (latest?.stalker != source.stalker || stalkerRouteIdentity() != route)
            throw StalkerException(StalkerError.STALE_SESSION)
        stalkerCheckMediaRoute(stream.url, route, config.portalUrl)
        ensureCurrent(source)
        LiveTvPreferences.currentPlaylists().firstOrNull { it.id == sourceId }?.let { checkPermission(it, ref) }
            ?: throw StalkerException(StalkerError.STALE_SESSION)
        val prepared = LiveTvPreferences.withCurrentPlaylist(source) {
        val lastVerifiedSources = atomic(LiveTvPreferences.rawData.value[LiveTvKeys.playlists])
        StalkerPlaybackStream(stream.url, stream.headers, StalkerMediaPolicy(authorization, route, config.portalUrl,
            requireHttps = stream.url.startsWith("https://", true)) {
            if (!session.active.value) false else {
                val currentSources = LiveTvPreferences.rawData.value[LiveTvKeys.playlists]
                if (currentSources == lastVerifiedSources.value) true else {
                    val allowed = LiveTvPreferences.currentPlaylists().any {
                        it.id == source.id && it.enabled && it.isStalker && it.stalker == source.stalker &&
                            (if (ref.kind == StalkerContentKind.LIVE || ref.kind == StalkerContentKind.ARCHIVE) it.importLive else it.importVod)
                    }
                    if (allowed) lastVerifiedSources.value = currentSources
                    allowed
                }
            }
        })
        }
        val streamType = stalkerProbeStreamType(prepared.url, prepared.mediaPolicy)
        return LiveTvPreferences.withCurrentPlaylist(source) { prepared.copy(streamType = streamType) }
    }

    private fun checkPermission(source: PlaylistSource, ref: StalkerStreamRef) {
        val allowed = when (ref.kind) {
            StalkerContentKind.LIVE, StalkerContentKind.ARCHIVE -> source.importLive
            StalkerContentKind.VOD, StalkerContentKind.SERIES -> source.importVod
        }
        if (!source.enabled || !allowed) throw StalkerException(StalkerError.STALE_SESSION)
    }

    /** Cache locator only. Commands and account identity never appear in generic player URLs. */
    fun locator(sourceId: String, ref: StalkerStreamRef): String =
        "stalker://${sourceId.encodeURLParameter()}/${ref.kind.name.lowercase()}/${ref.providerId.encodeURLParameter()}"

    fun isLocator(url: String): Boolean = url.startsWith("stalker:", ignoreCase = true)

    internal fun parsePortalTime(text: String, timezone: String): Long? {
        if (!ZoneFinder.isKnown(timezone)) return null
        val values = PORTAL_TIME
            .matchEntire(text.trim())?.groupValues?.drop(1)?.map { it.toIntOrNull() ?: return null } ?: return null
        val (year, month, day, hour, minute) = values
        val second = values[5]
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        val epoch = CivilTime.toEpochMs(year, month, day, hour, minute, second, timezone)
        return epoch.takeIf { CivilTime.at(it, timezone) == CivilTime(year, month, day, hour, minute, second) }
    }

    private val PORTAL_TIME = Regex("""^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})$""")
}

/** Android implements these with the existing fail-closed VPN transport, never a direct fallback. */
internal expect suspend fun stalkerRouteIdentity(): String
internal expect suspend fun stalkerHttpGet(url: String, headers: Map<String, String>, route: String): StalkerHttpResponse
internal expect suspend fun stalkerHttpGuide(url: String, headers: Map<String, String>, route: String, onRow: (String?, StalkerGuideRow) -> Unit): StalkerGuideHttpResponse
internal expect suspend fun stalkerCheckMediaRoute(url: String, route: String, portalUrl: String)
internal expect fun stalkerCheckRouteNow(route: String, portalUrl: String?, actualUrl: String)
internal expect suspend fun stalkerProbeStreamType(url: String, policy: StalkerMediaPolicy): String?

/** Ephemeral request context, passed separately from headers and never persisted or exported. */
class StalkerMediaPolicy internal constructor(
    private val authorization: StalkerMediaAuthorization,
    private val route: String,
    private val portalUrl: String? = null,
    private val requireHttps: Boolean = false,
    private val stillCurrent: () -> Boolean,
) {
    /** Android installs one protected connection pool per context, never per HLS piece. */
    internal val platformTransport = atomic<Any?>(null)
    override fun toString(): String = "StalkerMediaPolicy(<redacted>)"
    fun headersFor(actualUrl: String): Map<String, String> {
        if (requireHttps && !actualUrl.startsWith("https://", true)) throw StalkerException(StalkerError.LINK_UNAVAILABLE)
        if (!stillCurrent()) throw StalkerException(StalkerError.STALE_SESSION)
        stalkerCheckRouteNow(route, portalUrl, actualUrl)
        return authorization.headersFor(actualUrl)
    }
}

internal data class StalkerPlaybackStream(val url: String, val headers: Map<String, String>, val mediaPolicy: StalkerMediaPolicy, val streamType: String? = null) {
    override fun toString(): String = "StalkerPlaybackStream(<redacted>)"
}
