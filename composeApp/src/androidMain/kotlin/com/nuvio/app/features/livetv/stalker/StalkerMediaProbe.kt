package com.nuvio.app.features.livetv.stalker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Only unknown media paths need probing. Never consumes a full media response or stores its URL. */
internal actual suspend fun stalkerProbeStreamType(url: String, policy: StalkerMediaPolicy): String? {
    val path = url.toHttpUrlOrNull()?.encodedPath?.lowercase() ?: return null
    if (path.endsWith(".m3u8")) return "hls"
    if (path.endsWith(".mpd")) return "dash"
    if (KNOWN_MEDIA_EXTENSIONS.any { path.endsWith(it) }) return null
    return withContext(Dispatchers.IO) {
        withTimeoutOrNull(1_200) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_200)
            try {
                suspend fun open(method: String): Pair<Call, Response> {
                    val request = Request.Builder().url(url).apply {
                        if (method == "HEAD") head() else header("Range", "bytes=0-8191").get()
                    }.build()
                    val call = StalkerMediaHttp.calls(policy).newCall(request)
                    val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
                    call.timeout().timeout(remaining, TimeUnit.MILLISECONDS)
                    return try { call to call.awaitProbe() }
                    catch (failure: Throwable) { call.cancel(); throw failure }
                }
                var mayTryGet = false
                val (headCall, headResponse) = open("HEAD")
                val head = try {
                    val contentType = headResponse.header("Content-Type")
                    mayTryGet = (headResponse.isSuccessful && !isProgressiveType(contentType)) || headResponse.code in setOf(403, 405, 501)
                    if (headResponse.isSuccessful) streamType(contentType) else null
                } finally {
                    headCall.cancel()
                    headResponse.close()
                }
                if (head != null) return@withTimeoutOrNull head
                if (!mayTryGet || System.nanoTime() >= deadline) return@withTimeoutOrNull null
                val (getCall, getResponse) = open("GET")
                readStalkerProbeBody(getCall, getResponse)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }
}

private val KNOWN_MEDIA_EXTENSIONS = listOf(".ts", ".m2ts", ".mp4", ".mkv", ".webm", ".mov", ".avi", ".mpg", ".mpeg", ".m4v", ".m4s", ".flv", ".mp3", ".aac", ".flac")

/** Cancel before close: OkHttp otherwise drains more of a live body to reuse its connection. */
internal fun readStalkerProbeBody(call: Call, response: Response): String? = try {
    if (!response.isSuccessful) null
    else streamType(response.header("Content-Type")) ?: if (isProgressiveType(response.header("Content-Type"))) null else {
        val text = response.peekBody(8 * 1024).string().trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        when {
            text.startsWith("#EXTM3U") -> "hls"
            text.contains("<MPD") && (text.startsWith("<?xml") || text.startsWith("<MPD")) -> "dash"
            else -> null
        }
    }
} finally {
    call.cancel()
    response.close()
}

private val PROGRESSIVE_MEDIA_TYPES = setOf(
    "video/mp2t", "video/mpeg", "video/mp4", "application/mp4", "video/x-matroska", "video/webm", "video/quicktime", "video/x-msvideo", "video/x-flv",
    "audio/mpeg", "audio/mp4", "audio/aac", "audio/flac", "audio/ogg", "video/ogg", "application/ogg",
)

private fun isProgressiveType(type: String?): Boolean = type?.substringBefore(';')?.trim()?.lowercase() in PROGRESSIVE_MEDIA_TYPES

private fun streamType(type: String?): String? = when (type?.substringBefore(';')?.trim()?.lowercase()) {
    "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl" -> "hls"
    "application/dash+xml" -> "dash"
    else -> null
}

private suspend fun Call.awaitProbe(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(IOException("The media type could not be checked."))
        }
        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) { _, value, _ -> call.cancel(); value.close() }
            else { call.cancel(); response.close() }
        }
    })
}
