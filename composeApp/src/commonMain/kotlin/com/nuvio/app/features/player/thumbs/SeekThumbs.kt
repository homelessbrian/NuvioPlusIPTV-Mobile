package com.nuvio.app.features.player.thumbs

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.StateFlow

/** When seek thumbnails are made (Settings > Playback > Seek Thumbnails). */
enum class SeekThumbWhen(val label: String, val detail: String) {
    SEEKING("Only When Paused or Seeking", "The lightest choice: nothing is made while the video just plays"),
    WATCHING("While Watching", "Also made in the background while the video plays, when it has loaded well ahead"),
    RIGHT_AWAY("Right Away", "The whole video, from the start, as soon as it opens. Uses the most data"),
}

data class SeekThumbValues(
    val enabled: Boolean = false,
    val include4k: Boolean = false,
    val whenToMake: SeekThumbWhen = SeekThumbWhen.SEEKING,
    val storageMb: Int = 50,
    /** When thumbnails were last paused to keep playback smooth (0: never). */
    val lastLowMemoryAt: Long = 0L,
)

val SEEK_THUMB_STORAGE_CHOICES = listOf(50, 100, 250, 500)

/** What to make pictures from. */
sealed class SeekThumbSource {
    abstract val key: String
    /** A one-file video over HTTP (MP4, MKV and similar). */
    data class Remote(val url: String, val headers: Map<String, String>, override val key: String) : SeekThumbSource()
    /** A video file on this device (a recording). */
    data class Local(val path: String, override val key: String) : SeekThumbSource()
    /** A streamed (HLS) link, read piece by piece from its smallest version. */
    data class Hls(
        val url: String,
        val headers: Map<String, String>,
        override val key: String,
        val policy: com.nuvio.app.features.livetv.stalker.StalkerMediaPolicy? = null,
    ) : SeekThumbSource()
}

/** What the player tells the thumbnail maker about playback (read from its own thread). */
class SeekThumbPlayback(
    val isPlaying: () -> Boolean,
    val positionMs: () -> Long,
    /** How far the player has loaded ahead of where it is. */
    val bufferedAheadMs: () -> Long,
)

object SeekThumbMessages {
    const val MAKING = "Making thumbnails…"
    const val LOW_MEMORY = "Thumbnails paused: low memory"
    const val FOUR_K_OFF = "4K thumbnails off in settings"
    const val TORRENT = "No thumbnails for torrents"
    const val DASH = "No thumbnails for DASH links"
    const val CATCHUP_DIRECT = "No thumbnails for this catch-up (it would need a second connection to your provider)"
    const val PROTECTED = "This stream is protected: no thumbnails"
}

/**
 * Seek thumbnails: small pictures along the seek bar, made on this device from the video itself.
 * Android only (made with Android's own frame reader); on other platforms [supported] is false
 * and nothing is shown.
 */
expect object SeekThumbsPlatform {
    val supported: Boolean
    val settings: StateFlow<SeekThumbValues>
    fun updateSettings(change: (SeekThumbValues) -> SeekThumbValues)
    /** The device counts as low on memory (most cheaper phones and older tablets). */
    fun isLowMemoryDevice(): Boolean

    /** Goes up whenever a picture is ready, so the preview redraws. */
    val version: StateFlow<Int>
    /** What the preview spot says when it has no picture (null: nothing). */
    val message: StateFlow<String?>

    fun open(source: SeekThumbSource, durationMs: Long, videoHeight: Int, playback: SeekThumbPlayback)
    fun unsupported(reason: String)
    fun close()
    fun want(positionMs: Long)
    fun frame(positionMs: Long): ImageBitmap?

    /** Bytes the saved thumbnails take up. Reads the folder: not on the main thread. */
    fun savedBytes(): Long
    fun clearAll()
}
