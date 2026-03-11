package com.nuvio.app.features.player

import android.net.Uri
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@androidx.annotation.OptIn(UnstableApi::class)
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
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnSnapshot = rememberUpdatedState(onSnapshot)
    val latestOnError = rememberUpdatedState(onError)
    val exoPlayer = remember(sourceUrl) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(sourceUrl))
            prepare()
            this.playWhenReady = playWhenReady
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                latestOnError.value(error.localizedMessage ?: "Unable to play this stream.")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    latestOnError.value(null)
                }
                latestOnSnapshot.value(exoPlayer.snapshot())
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                latestOnSnapshot.value(exoPlayer.snapshot())
            }

            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                latestOnSnapshot.value(exoPlayer.snapshot())
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    DisposableEffect(exoPlayer, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> exoPlayer.playWhenReady = playWhenReady
                Lifecycle.Event.ON_STOP -> exoPlayer.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    LaunchedEffect(exoPlayer, playWhenReady) {
        exoPlayer.playWhenReady = playWhenReady
        latestOnSnapshot.value(exoPlayer.snapshot())
    }

    LaunchedEffect(exoPlayer) {
        onControllerReady(
            object : PlayerEngineController {
                override fun play() {
                    exoPlayer.playWhenReady = true
                    exoPlayer.play()
                }

                override fun pause() {
                    exoPlayer.pause()
                }

                override fun seekTo(positionMs: Long) {
                    exoPlayer.seekTo(positionMs.coerceAtLeast(0L))
                }

                override fun seekBy(offsetMs: Long) {
                    exoPlayer.seekTo((exoPlayer.currentPosition + offsetMs).coerceAtLeast(0L))
                }

                override fun retry() {
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                }

                override fun setPlaybackSpeed(speed: Float) {
                    exoPlayer.setPlaybackSpeed(speed)
                }

                override fun getAudioTracks(): List<AudioTrack> =
                    exoPlayer.extractAudioTracks()

                override fun getSubtitleTracks(): List<SubtitleTrack> =
                    exoPlayer.extractSubtitleTracks()

                override fun selectAudioTrack(index: Int) {
                    exoPlayer.selectTrackByIndex(C.TRACK_TYPE_AUDIO, index)
                }

                override fun selectSubtitleTrack(index: Int) {
                    if (index < 0) {
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                        return
                    }
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .build()
                    exoPlayer.selectTrackByIndex(C.TRACK_TYPE_TEXT, index)
                }

                override fun setSubtitleUri(url: String) {
                    val currentPosition = exoPlayer.currentPosition
                    val wasPlaying = exoPlayer.isPlaying
                    val currentMediaItem = exoPlayer.currentMediaItem ?: return
                    val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
                        .setMimeType(guessSubtitleMime(url))
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                        .build()
                    val newMediaItem = currentMediaItem.buildUpon()
                        .setSubtitleConfigurations(listOf(subtitleConfig))
                        .build()
                    exoPlayer.setMediaItem(newMediaItem, currentPosition)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = wasPlaying
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .build()
                }

                override fun clearExternalSubtitle() {
                    val currentPosition = exoPlayer.currentPosition
                    val wasPlaying = exoPlayer.isPlaying
                    val currentMediaItem = exoPlayer.currentMediaItem ?: return
                    val newMediaItem = currentMediaItem.buildUpon()
                        .setSubtitleConfigurations(emptyList())
                        .build()
                    exoPlayer.setMediaItem(newMediaItem, currentPosition)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = wasPlaying
                }
            }
        )
    }

    LaunchedEffect(exoPlayer) {
        while (isActive) {
            latestOnSnapshot.value(exoPlayer.snapshot())
            delay(250L)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                useController = false
                layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                player = exoPlayer
                keepScreenOn = true
                this.resizeMode = resizeMode.toExoResizeMode()
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { playerView ->
            playerView.player = exoPlayer
            playerView.resizeMode = resizeMode.toExoResizeMode()
        },
    )
}

private fun ExoPlayer.snapshot(): PlayerPlaybackSnapshot =
    PlayerPlaybackSnapshot(
        isLoading = playbackState == Player.STATE_IDLE || playbackState == Player.STATE_BUFFERING,
        isPlaying = isPlaying,
        isEnded = playbackState == Player.STATE_ENDED,
        durationMs = duration.coerceAtLeast(0L),
        positionMs = currentPosition.coerceAtLeast(0L),
        bufferedPositionMs = bufferedPosition.coerceAtLeast(0L),
        playbackSpeed = playbackParameters.speed,
    )

private fun PlayerResizeMode.toExoResizeMode(): Int =
    when (this) {
        PlayerResizeMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        PlayerResizeMode.Fill -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        PlayerResizeMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }

private fun ExoPlayer.extractAudioTracks(): List<AudioTrack> {
    val tracks = mutableListOf<AudioTrack>()
    var idx = 0
    for (group in currentTracks.groups) {
        if (group.type != C.TRACK_TYPE_AUDIO) continue
        val format = group.mediaTrackGroup.getFormat(0)
        tracks.add(
            AudioTrack(
                index = idx,
                id = format.id ?: idx.toString(),
                label = format.label ?: "",
                language = format.language,
                isSelected = group.isSelected,
            )
        )
        idx++
    }
    return tracks
}

private fun ExoPlayer.extractSubtitleTracks(): List<SubtitleTrack> {
    val tracks = mutableListOf<SubtitleTrack>()
    var idx = 0
    for (group in currentTracks.groups) {
        if (group.type != C.TRACK_TYPE_TEXT) continue
        val format = group.mediaTrackGroup.getFormat(0)
        tracks.add(
            SubtitleTrack(
                index = idx,
                id = format.id ?: idx.toString(),
                label = format.label ?: "",
                language = format.language,
                isSelected = group.isSelected,
            )
        )
        idx++
    }
    return tracks
}

private fun ExoPlayer.selectTrackByIndex(trackType: Int, targetIndex: Int) {
    var idx = 0
    for (group in currentTracks.groups) {
        if (group.type != trackType) continue
        if (idx == targetIndex) {
            trackSelectionParameters = trackSelectionParameters
                .buildUpon()
                .setOverrideForType(
                    TrackSelectionOverride(group.mediaTrackGroup, listOf(0))
                )
                .build()
            return
        }
        idx++
    }
}

private fun guessSubtitleMime(url: String): String {
    val lower = url.lowercase()
    return when {
        lower.contains(".srt") -> MimeTypes.APPLICATION_SUBRIP
        lower.contains(".vtt") || lower.contains(".webvtt") -> MimeTypes.TEXT_VTT
        lower.contains(".ass") || lower.contains(".ssa") -> MimeTypes.TEXT_SSA
        lower.contains(".ttml") || lower.contains(".dfxp") || lower.contains(".xml") -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.TEXT_VTT
    }
}
