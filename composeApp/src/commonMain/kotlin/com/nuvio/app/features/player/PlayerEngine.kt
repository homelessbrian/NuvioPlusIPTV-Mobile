package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

interface PlayerEngineController {
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekBy(offsetMs: Long)
    fun retry()
    fun setPlaybackSpeed(speed: Float)
}

@Composable
expect fun PlatformPlayerSurface(
    sourceUrl: String,
    modifier: Modifier = Modifier,
    playWhenReady: Boolean = true,
    resizeMode: PlayerResizeMode = PlayerResizeMode.Fit,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
)
