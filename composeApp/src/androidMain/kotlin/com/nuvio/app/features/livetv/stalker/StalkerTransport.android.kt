package com.nuvio.app.features.livetv.stalker

import com.nuvio.stalker.*
import com.nuvio.vpn.android.VpnHttpClient
import com.nuvio.vpn.android.VpnPlaybackProtection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

private val portalHttp by lazy {
    VpnHttpClient.wrap(OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(65, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build())
}
private val portalGuideHttp by lazy {
    VpnHttpClient.wrap(OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build())
}

/** HTTP-only can authenticate directly on HTTPS but play through a different IP on HTTP. */
internal actual suspend fun stalkerRouteIdentity(): String = withContext(Dispatchers.IO) {
    if (VpnPlaybackProtection.activeUserProfile() <= 0) {
        com.nuvio.app.features.livetv.record.RecordingPlatform.context()?.let { context ->
            VpnPlaybackProtection.initializeActiveUserProfile(com.nuvio.app.features.profiles.ProfileStorage.savedActiveProfileForBackground(context))
        }
    }
    try { VpnPlaybackProtection.requireUniformHttpRouting() }
    catch (_: IOException) {
        throw StalkerRoutingException()
    }
}

internal actual suspend fun stalkerCheckMediaRoute(url: String, route: String, portalUrl: String) {
    if (url.toHttpUrlOrNull() == null) throw StalkerException(StalkerError.LINK_UNAVAILABLE)
    if (!consistentStalkerRoutes(route, portalUrl, url)) throw StalkerRouteMismatchException()
    if (stalkerRouteIdentity() != route) throw StalkerException(StalkerError.STALE_SESSION)
    withContext(Dispatchers.IO) {
        try { VpnPlaybackProtection.beforeRequest(url) }
        catch (_: IOException) { throw StalkerException(StalkerError.NETWORK) }
    }
    if (stalkerRouteIdentity() != route) throw StalkerException(StalkerError.STALE_SESSION)
}

internal actual fun stalkerCheckRouteNow(route: String, portalUrl: String?, actualUrl: String) {
    try {
        if (VpnPlaybackProtection.requireUniformHttpRouting() != route) throw StalkerException(StalkerError.STALE_SESSION)
    } catch (_: IOException) { throw StalkerException(StalkerError.NETWORK) }
    if (portalUrl != null && !consistentStalkerRoutes(route, portalUrl, actualUrl)) throw StalkerRouteMismatchException()
}

internal fun consistentStalkerRoutes(route: String, portalUrl: String, mediaUrl: String): Boolean = runCatching {
    val mode = com.nuvio.vpn.core.ProtectionMode.valueOf(route.substringBefore(':'))
    val portal = com.nuvio.vpn.core.StreamRoutePolicy.decide(java.net.URI(portalUrl), mode, true)
    val media = com.nuvio.vpn.core.StreamRoutePolicy.decide(java.net.URI(mediaUrl), mode, true)
    portal != com.nuvio.vpn.core.StreamRoutePolicy.Route.BLOCK && portal == media
}.getOrDefault(false)

internal actual suspend fun stalkerHttpGet(url: String, headers: Map<String, String>, route: String): StalkerHttpResponse {
    val reply = portalGet(url, headers, route) { response ->
        val content = response.body ?: return@portalGet ""
        content.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 32 * 1024 * 1024) throw StalkerException(StalkerError.TOO_LARGE)
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }
    return StalkerHttpResponse(reply.status, reply.value.orEmpty(), finalUrl = reply.url)
}

internal actual suspend fun stalkerHttpGuide(
    url: String, headers: Map<String, String>, route: String,
    onRow: (String?, StalkerGuideRow) -> Unit,
): StalkerGuideHttpResponse {
    val reply = portalGet(url, headers, route, streaming = true) { response ->
        if (response.code !in 200..299) return@portalGet null
        response.body?.charStream()?.use { reader -> StalkerGuideReader.read(reader, onRow) }
            ?: StalkerError.INVALID_RESPONSE
    }
    return StalkerGuideHttpResponse(reply.status, reply.value, reply.url)
}

private data class PortalReply<T>(val status: Int, val url: String, val location: String?, val value: T?)

/** Parsing remains on OkHttp's worker. Cancellation closes the active call during body reads too. */
private suspend fun <T> portalGet(url: String, headers: Map<String, String>, route: String, streaming: Boolean = false, read: (Response) -> T): PortalReply<T> {
    val origin = url.toHttpUrlOrNull() ?: throw StalkerException(StalkerError.INVALID_CONFIG)
    var target = origin
    repeat(6) { redirects ->
        if (stalkerRouteIdentity() != route) throw StalkerException(StalkerError.STALE_SESSION)
        val request = try {
            Request.Builder().url(target).get().apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        } catch (_: IllegalArgumentException) { throw StalkerException(StalkerError.INVALID_CONFIG) }
        val reply = (if (streaming) portalGuideHttp else portalHttp).newCall(request).readPortalResponse { response ->
            val redirect = response.code in listOf(301, 302, 303, 307, 308)
            PortalReply(response.code, response.request.url.toString(), response.header("Location"), if (redirect) null else read(response))
        }
        if (stalkerRouteIdentity() != route) throw StalkerException(StalkerError.STALE_SESSION)
        if (reply.status in listOf(301, 302, 303, 307, 308)) {
            val next = reply.location?.let { target.resolve(it) } ?: throw StalkerException(StalkerError.INVALID_RESPONSE)
            if (redirects == 5 || !sameOrigin(origin, next)) throw StalkerException(StalkerError.INVALID_RESPONSE)
            target = next
        } else return reply
    }
    throw StalkerException(StalkerError.INVALID_RESPONSE)
}

private fun sameOrigin(first: HttpUrl, next: HttpUrl): Boolean =
    first.scheme == next.scheme && first.host == next.host && first.port == next.port &&
        next.username.isEmpty() && next.password.isEmpty()

private suspend fun <T> Call.readPortalResponse(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(StalkerException(StalkerError.NETWORK)))
        }
        override fun onResponse(call: Call, response: Response) {
            val result = try { response.use { Result.success(read(it)) } }
            catch (failure: StalkerException) { Result.failure(failure) }
            catch (_: Exception) { Result.failure(StalkerException(StalkerError.NETWORK)) }
            if (continuation.isActive) continuation.resumeWith(result)
        }
    })
}
