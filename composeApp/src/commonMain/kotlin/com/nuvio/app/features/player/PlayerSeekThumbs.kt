package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.nuvio.app.features.livetv.player.LiveTvPlayback
import com.nuvio.app.features.player.thumbs.SeekThumbMessages
import com.nuvio.app.features.player.thumbs.SeekThumbPlayback
import com.nuvio.app.features.player.thumbs.SeekThumbSource
import com.nuvio.app.features.player.thumbs.SeekThumbsPlatform
import kotlin.concurrent.Volatile
import kotlinx.coroutines.delay

/**
 * Seek thumbnails for the player: checks every couple of seconds what's playing, so a source
 * switch, the next episode or another channel gets its own pictures. Movies and shows (one file
 * or a streamed HLS link), recordings, sports replays and HLS catch-up; not live channels,
 * torrents or DASH. A catch-up file straight from the provider gets none: reading it again
 * would need a second connection on the provider account.
 */
@Composable
internal fun PlayerScreenRuntime.BindSeekThumbs() {
    if (!SeekThumbsPlatform.supported) return
    val settings by SeekThumbsPlatform.settings.collectAsState()
    val seen = remember { SeekThumbSeen() }
    LaunchedEffect(settings.enabled) {
        if (!settings.enabled) { SeekThumbsPlatform.close(); return@LaunchedEffect }
        var n = 0
        while (true) {
            seen.read(playbackSnapshot)
            if (n++ % 4 == 0) updateSeekThumbs(seen)
            delay(500)
        }
    }
    DisposableEffect(Unit) { onDispose { SeekThumbsPlatform.close() } }
}

/** The player's state at the last look, for the thumbnail maker's own thread. */
private class SeekThumbSeen {
    @Volatile var playing = false
    @Volatile var positionMs = 0L
    @Volatile var aheadMs = 0L

    fun read(s: PlayerPlaybackSnapshot) {
        playing = s.isPlaying
        positionMs = s.positionMs.coerceAtLeast(0L)
        aheadMs = (s.bufferedPositionMs - s.positionMs).coerceAtLeast(0L)
    }

    val playback = SeekThumbPlayback(
        isPlaying = { playing },
        positionMs = { positionMs },
        bufferedAheadMs = { aheadMs },
    )
}

private fun PlayerScreenRuntime.updateSeekThumbs(seen: SeekThumbSeen) {
    val snapshot = playbackSnapshot
    val url = activeSourceUrl
    if (!activeTorrentInfoHash.isNullOrBlank()) { SeekThumbsPlatform.unsupported(SeekThumbMessages.TORRENT); return }
    // A live channel: nothing to seek through.
    if (isLiveTv && !LiveTvPlayback.isCatchup(url)) { SeekThumbsPlatform.close(); return }
    if (url.isBlank() || snapshot.durationMs <= 0L) return
    val path = url.substringBefore('#').substringBefore('?').trimEnd('/').lowercase()
    val hls = activeStreamType.equals("hls", ignoreCase = true) || path.endsWith(".m3u8") || path.endsWith(".m3u")
    val dash = activeStreamType.equals("dash", ignoreCase = true) || path.endsWith(".mpd") || path.endsWith(".ism") || path.endsWith(".ism/manifest")
    val file = path.substringAfterLast('/')
    val key = "${activeSourceIdentityKey ?: url}|$file|${snapshot.durationMs / 1000}"
    val source = when {
        url.startsWith("file:", ignoreCase = true) || url.startsWith("/") ->
            SeekThumbSource.Local(url.removePrefix("file://").removePrefix("file:").replace("%20", " "), "local|$key")
        !url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true) -> {
            SeekThumbsPlatform.unsupported("No thumbnails for this kind of link"); return
        }
        dash -> { SeekThumbsPlatform.unsupported(SeekThumbMessages.DASH); return }
        hls -> SeekThumbSource.Hls(url, activeSourceHeaders, "hls|$key|${url.hashCode()}", activeStalkerMediaPolicy)
        LiveTvPlayback.isCatchup(url) -> { SeekThumbsPlatform.unsupported(SeekThumbMessages.CATCHUP_DIRECT); return }
        else -> SeekThumbSource.Remote(url, activeSourceHeaders, key)
    }
    SeekThumbsPlatform.open(source, snapshot.durationMs, snapshot.videoHeight, seen.playback)
}
