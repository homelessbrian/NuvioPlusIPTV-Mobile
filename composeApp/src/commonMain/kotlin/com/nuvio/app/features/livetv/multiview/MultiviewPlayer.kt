package com.nuvio.app.features.livetv.multiview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.features.livetv.model.LiveChannel
import kotlinx.coroutines.flow.StateFlow

/** Native pane playback is currently available on Android only. */
expect val isMultiviewSupported: Boolean

/** A pane owns its decoder and connection. Implementations start muted and release idempotently. */
interface MultiviewPlayer {
    val state: StateFlow<MultiviewTileState>
    fun play(channel: LiveChannel)
    fun setMuted(muted: Boolean)
    fun setPaused(paused: Boolean)
    fun release()
}

data class MultiviewTileState(
    val isLoading: Boolean = false,
    val isPlaying: Boolean = false,
    /** Only a fixed, credential-free description may cross this boundary. */
    val error: String? = null,
)

@Composable
expect fun rememberMultiviewPlayer(mayPlayChannel: ((LiveChannel) -> Boolean)? = null): MultiviewPlayer

@Composable
expect fun PlatformMultiviewSurface(player: MultiviewPlayer, modifier: Modifier = Modifier)
