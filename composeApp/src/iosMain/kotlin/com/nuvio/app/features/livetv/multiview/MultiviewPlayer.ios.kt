package com.nuvio.app.features.livetv.multiview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.nuvio.app.features.livetv.model.LiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual val isMultiviewSupported: Boolean = false

@Composable
actual fun rememberMultiviewPlayer(mayPlayChannel: ((LiveChannel) -> Boolean)?): MultiviewPlayer = remember { UnsupportedIosMultiviewPlayer() }

@Composable
actual fun PlatformMultiviewSurface(player: MultiviewPlayer, modifier: Modifier) = Unit

/** Keeps the common contract available while Multiview is limited to Android. */
private class UnsupportedIosMultiviewPlayer : MultiviewPlayer {
    override val state: StateFlow<MultiviewTileState> = MutableStateFlow(
        MultiviewTileState(error = "Multiview is available on Android."),
    ).asStateFlow()

    override fun play(channel: LiveChannel) = Unit
    override fun setMuted(muted: Boolean) = Unit
    override fun setPaused(paused: Boolean) = Unit
    override fun release() = Unit
}
