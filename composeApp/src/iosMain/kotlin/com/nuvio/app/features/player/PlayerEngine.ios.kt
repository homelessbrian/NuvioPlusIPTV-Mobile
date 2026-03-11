package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitViewController
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val TAG = "NuvioiOSPlayer"

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PlatformPlayerSurface(
    sourceUrl: String,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val latestOnSnapshot = rememberUpdatedState(onSnapshot)
    val latestOnError = rememberUpdatedState(onError)

    val bridge = remember(sourceUrl) {
        NuvioPlayerBridgeFactory.create()
    }

    if (bridge == null) {
        LaunchedEffect(Unit) {
            onError("MPV player engine not available. Please rebuild the app.")
        }
        return
    }

    // Create controller
    LaunchedEffect(bridge) {
        onControllerReady(
            object : PlayerEngineController {
                override fun play() {
                    bridge.play()
                }

                override fun pause() {
                    bridge.pause()
                }

                override fun seekTo(positionMs: Long) {
                    bridge.seekTo(positionMs)
                }

                override fun seekBy(offsetMs: Long) {
                    bridge.seekBy(offsetMs)
                }

                override fun retry() {
                    bridge.retry()
                }

                override fun setPlaybackSpeed(speed: Float) {
                    bridge.setPlaybackSpeed(speed)
                }

                override fun getAudioTracks(): List<AudioTrack> {
                    val count = bridge.getAudioTrackCount()
                    return (0 until count).map { i ->
                        AudioTrack(
                            index = bridge.getAudioTrackIndex(i),
                            id = bridge.getAudioTrackId(i),
                            label = bridge.getAudioTrackLabel(i),
                            language = bridge.getAudioTrackLang(i),
                            isSelected = bridge.isAudioTrackSelected(i),
                        )
                    }
                }

                override fun getSubtitleTracks(): List<SubtitleTrack> {
                    val count = bridge.getSubtitleTrackCount()
                    val tracks = (0 until count).map { i ->
                        SubtitleTrack(
                            index = bridge.getSubtitleTrackIndex(i),
                            id = bridge.getSubtitleTrackId(i),
                            label = bridge.getSubtitleTrackLabel(i),
                            language = bridge.getSubtitleTrackLang(i),
                            isSelected = bridge.isSubtitleTrackSelected(i),
                        )
                    }
                    Logger.d(TAG) { "getSubtitleTracks: found ${tracks.size} tracks" }
                    return tracks
                }

                override fun selectAudioTrack(index: Int) {
                    // Convert from list index to mpv track id
                    val count = bridge.getAudioTrackCount()
                    if (index in 0 until count) {
                        val trackId = bridge.getAudioTrackId(index).toIntOrNull() ?: (index + 1)
                        bridge.selectAudioTrack(trackId)
                    }
                }

                override fun selectSubtitleTrack(index: Int) {
                    if (index < 0) {
                        bridge.selectSubtitleTrack(-1) // disable
                    } else {
                        val count = bridge.getSubtitleTrackCount()
                        if (index in 0 until count) {
                            val trackId = bridge.getSubtitleTrackId(index).toIntOrNull() ?: (index + 1)
                            bridge.selectSubtitleTrack(trackId)
                        }
                    }
                }

                override fun setSubtitleUri(url: String) {
                    Logger.d(TAG) { "setSubtitleUri: $url" }
                    bridge.setSubtitleUrl(url)
                }

                override fun clearExternalSubtitle() {
                    bridge.clearExternalSubtitle()
                }

                override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
                    val trackId = if (trackIndex < 0) -1 else {
                        val count = bridge.getSubtitleTrackCount()
                        if (trackIndex in 0 until count) {
                            bridge.getSubtitleTrackId(trackIndex).toIntOrNull() ?: (trackIndex + 1)
                        } else trackIndex + 1
                    }
                    bridge.clearExternalSubtitleAndSelect(trackId)
                }
            }
        )
    }

    // Load file and set initial state
    LaunchedEffect(bridge, sourceUrl) {
        bridge.loadFile(sourceUrl)
        if (playWhenReady) {
            bridge.play()
        } else {
            bridge.pause()
        }
    }

    // Update playWhenReady
    LaunchedEffect(bridge, playWhenReady) {
        if (playWhenReady) bridge.play() else bridge.pause()
    }

    // Update resize mode
    LaunchedEffect(bridge, resizeMode) {
        bridge.setResizeMode(
            when (resizeMode) {
                PlayerResizeMode.Fit -> 0
                PlayerResizeMode.Fill -> 1
                PlayerResizeMode.Zoom -> 2
            }
        )
    }

    // Polling for snapshots
    LaunchedEffect(bridge) {
        while (isActive) {
            val snapshot = PlayerPlaybackSnapshot(
                isLoading = bridge.getIsLoading(),
                isPlaying = bridge.getIsPlaying(),
                isEnded = bridge.getIsEnded(),
                durationMs = bridge.getDurationMs(),
                positionMs = bridge.getPositionMs(),
                bufferedPositionMs = bridge.getBufferedMs(),
                playbackSpeed = bridge.getPlaybackSpeed(),
            )
            latestOnSnapshot.value(snapshot)
            delay(250L)
        }
    }

    // Cleanup
    DisposableEffect(bridge) {
        onDispose {
            bridge.destroy()
        }
    }

    // Render the player view
    UIKitViewController(
        factory = { bridge.createPlayerViewController() },
        modifier = modifier,
    )
}
