package com.nuvio.app.features.player

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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
