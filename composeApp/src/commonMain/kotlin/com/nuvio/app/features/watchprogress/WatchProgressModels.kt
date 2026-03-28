package com.nuvio.app.features.watchprogress

import kotlinx.serialization.Serializable

@Serializable
enum class ContinueWatchingSectionStyle {
    Wide,
    Poster,
}

@Serializable
data class WatchProgressEntry(
    val contentType: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val videoId: String,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val lastPositionMs: Long,
    val durationMs: Long,
    val lastUpdatedEpochMs: Long,
    val providerName: String? = null,
    val providerAddonId: String? = null,
    val lastStreamTitle: String? = null,
    val lastStreamSubtitle: String? = null,
    val lastSourceUrl: String? = null,
) {
    val progressFraction: Float
        get() = if (durationMs > 0L) {
            (lastPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val isEpisode: Boolean
        get() = seasonNumber != null && episodeNumber != null
}

data class WatchProgressUiState(
    val entries: List<WatchProgressEntry> = emptyList(),
) {
    val byVideoId: Map<String, WatchProgressEntry>
        get() = entries.associateBy { it.videoId }
}

data class WatchProgressPlaybackSession(
    val contentType: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val videoId: String,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val providerName: String? = null,
    val providerAddonId: String? = null,
    val lastStreamTitle: String? = null,
    val lastStreamSubtitle: String? = null,
    val lastSourceUrl: String? = null,
)

data class ContinueWatchingItem(
    val parentMetaId: String,
    val parentMetaType: String,
    val videoId: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val resumePositionMs: Long,
    val durationMs: Long,
    val progressFraction: Float,
)

data class ContinueWatchingPreferencesUiState(
    val isVisible: Boolean = true,
    val style: ContinueWatchingSectionStyle = ContinueWatchingSectionStyle.Wide,
)

internal fun WatchProgressEntry.toContinueWatchingItem(): ContinueWatchingItem {
    val subtitle = if (seasonNumber != null && episodeNumber != null) {
        buildString {
            append("S")
            append(seasonNumber)
            append("E")
            append(episodeNumber)
            episodeTitle?.takeIf { it.isNotBlank() }?.let {
                append(" • ")
                append(it)
            }
        }
    } else {
        "Movie"
    }

    return ContinueWatchingItem(
        parentMetaId = parentMetaId,
        parentMetaType = parentMetaType,
        videoId = videoId,
        title = title,
        subtitle = subtitle,
        imageUrl = episodeThumbnail ?: background ?: poster,
        logo = logo,
        poster = poster,
        background = background,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeTitle = episodeTitle,
        episodeThumbnail = episodeThumbnail,
        resumePositionMs = lastPositionMs,
        durationMs = durationMs,
        progressFraction = progressFraction,
    )
}

fun buildPlaybackVideoId(
    parentMetaId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    fallbackVideoId: String? = null,
): String =
    if (seasonNumber != null && episodeNumber != null) {
        "$parentMetaId:$seasonNumber:$episodeNumber"
    } else {
        fallbackVideoId?.takeIf { it.isNotBlank() } ?: parentMetaId
    }
