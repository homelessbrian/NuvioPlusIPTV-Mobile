package com.nuvio.app.features.player

import kotlinx.serialization.Serializable

@Serializable
data class PlayerRoute(
    val title: String,
    val sourceUrl: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val streamTitle: String,
    val streamSubtitle: String? = null,
    val providerName: String,
)

enum class PlayerResizeMode {
    Fit,
    Fill,
    Zoom,
}

data class PlayerPlaybackSnapshot(
    val isLoading: Boolean = true,
    val isPlaying: Boolean = false,
    val isEnded: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val playbackSpeed: Float = 1f,
)
