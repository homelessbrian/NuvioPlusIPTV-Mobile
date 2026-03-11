package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color

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
    val controller = remember {
        object : PlayerEngineController {
            override fun play() = Unit
            override fun pause() = Unit
            override fun seekTo(positionMs: Long) = Unit
            override fun seekBy(offsetMs: Long) = Unit
            override fun retry() = Unit
            override fun setPlaybackSpeed(speed: Float) = Unit
            override fun getAudioTracks(): List<AudioTrack> = emptyList()
            override fun getSubtitleTracks(): List<SubtitleTrack> = emptyList()
            override fun selectAudioTrack(index: Int) = Unit
            override fun selectSubtitleTrack(index: Int) = Unit
            override fun setSubtitleUri(url: String) = Unit
            override fun clearExternalSubtitle() = Unit
            override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
        }
    }

    LaunchedEffect(sourceUrl) {
        onControllerReady(controller)
        onSnapshot(PlayerPlaybackSnapshot(isLoading = false))
        onError("Playback is not implemented on iOS yet.")
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    )
}
