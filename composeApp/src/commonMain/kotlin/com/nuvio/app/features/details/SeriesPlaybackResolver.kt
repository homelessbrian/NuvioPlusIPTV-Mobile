package com.nuvio.app.features.details

import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import com.nuvio.app.features.watchprogress.resumeEntryForSeries

internal fun MetaDetails.sortedPlayableEpisodes(): List<MetaVideo> =
    videos
        .filter { it.season != null || it.episode != null }
        .sortedWith(
            compareBy<MetaVideo>(
                { it.season ?: Int.MAX_VALUE },
                { it.episode ?: Int.MAX_VALUE },
                { it.released ?: "" },
                { it.title },
            ),
        )

internal fun MetaDetails.firstPlayableEpisode(): MetaVideo? =
    sortedPlayableEpisodes().firstOrNull()

internal fun MetaDetails.firstReleasedPlayableEpisode(todayIsoDate: String): MetaVideo? =
    sortedPlayableEpisodes().firstOrNull { it.isReleasedBy(todayIsoDate) }

internal fun MetaDetails.nextReleasedEpisodeAfter(
    completedEntry: WatchProgressEntry,
    todayIsoDate: String,
): MetaVideo? {
    val sortedEpisodes = sortedPlayableEpisodes()
    return sortedEpisodes
        .dropWhile { episode ->
            buildPlaybackVideoId(
                parentMetaId = id,
                seasonNumber = episode.season,
                episodeNumber = episode.episode,
                fallbackVideoId = episode.id,
            ) != completedEntry.videoId
        }
        .drop(1)
        .firstOrNull { it.isReleasedBy(todayIsoDate) }
}

internal data class SeriesPrimaryAction(
    val label: String,
    val videoId: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
    val episodeThumbnail: String?,
    val resumePositionMs: Long?,
)

internal fun MetaDetails.seriesPrimaryAction(
    entries: List<WatchProgressEntry>,
    todayIsoDate: String,
): SeriesPrimaryAction? {
    val resumeEntry = entries.resumeEntryForSeries(id)
    if (resumeEntry != null) {
        return SeriesPrimaryAction(
            label = resumeEntry.resumeLabel(),
            videoId = resumeEntry.videoId,
            seasonNumber = resumeEntry.seasonNumber,
            episodeNumber = resumeEntry.episodeNumber,
            episodeTitle = resumeEntry.episodeTitle,
            episodeThumbnail = resumeEntry.episodeThumbnail,
            resumePositionMs = resumeEntry.lastPositionMs,
        )
    }

    val latestCompleted = entries
        .filter { it.parentMetaId == id && it.isCompleted }
        .maxByOrNull { it.lastUpdatedEpochMs }

    val nextEpisode = if (latestCompleted != null) {
        nextReleasedEpisodeAfter(
            completedEntry = latestCompleted,
            todayIsoDate = todayIsoDate,
        )
    } else {
        firstReleasedPlayableEpisode(todayIsoDate)
    }

    return nextEpisode?.let { episode ->
        SeriesPrimaryAction(
            label = if (latestCompleted != null) episode.upNextLabel() else episode.playLabel(),
            videoId = buildPlaybackVideoId(
                parentMetaId = id,
                seasonNumber = episode.season,
                episodeNumber = episode.episode,
                fallbackVideoId = episode.id,
            ),
            seasonNumber = episode.season,
            episodeNumber = episode.episode,
            episodeTitle = episode.title,
            episodeThumbnail = episode.thumbnail,
            resumePositionMs = null,
        )
    }
}

internal fun MetaVideo.playLabel(): String =
    if (season != null && episode != null) {
        "Play S${season}E${episode}"
    } else {
        "Play"
    }

internal fun MetaVideo.upNextLabel(): String =
    if (season != null && episode != null) {
        "Up Next S${season}E${episode}"
    } else {
        "Up Next"
    }

internal fun WatchProgressEntry.resumeLabel(): String =
    if (seasonNumber != null && episodeNumber != null) {
        "Resume S${seasonNumber}E${episodeNumber}"
    } else {
        "Resume"
    }

private fun MetaVideo.isReleasedBy(todayIsoDate: String): Boolean {
    val releaseDate = released
        ?.substringBefore('T')
        ?.takeIf { it.length == 10 }
        ?: return true
    return releaseDate <= todayIsoDate
}

