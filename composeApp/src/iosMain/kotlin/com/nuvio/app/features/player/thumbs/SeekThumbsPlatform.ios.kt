package com.nuvio.app.features.player.thumbs

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** No seek thumbnails on iPhone and iPad yet: the setting isn't shown and nothing is made. */
actual object SeekThumbsPlatform {
    actual val supported: Boolean = false
    private val _settings = MutableStateFlow(SeekThumbValues())
    actual val settings: StateFlow<SeekThumbValues> = _settings
    actual fun updateSettings(change: (SeekThumbValues) -> SeekThumbValues) {
        _settings.value = change(_settings.value)
    }
    actual fun isLowMemoryDevice(): Boolean = false
    actual val version: StateFlow<Int> = MutableStateFlow(0)
    actual val message: StateFlow<String?> = MutableStateFlow(null)
    actual fun open(source: SeekThumbSource, durationMs: Long, videoHeight: Int, playback: SeekThumbPlayback) = Unit
    actual fun unsupported(reason: String) = Unit
    actual fun close() = Unit
    actual fun want(positionMs: Long) = Unit
    actual fun frame(positionMs: Long): ImageBitmap? = null
    actual fun savedBytes(): Long = 0L
    actual fun clearAll() = Unit
}
