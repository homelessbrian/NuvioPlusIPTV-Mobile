package com.nuvio.app.features.details

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.nuvioPlatformExtraBottomPadding
import com.nuvio.app.features.details.components.DetailActionButtons
import com.nuvio.app.features.details.components.DetailCastSection
import com.nuvio.app.features.details.components.DetailHero
import com.nuvio.app.features.details.components.DetailMetaInfo
import com.nuvio.app.features.details.components.DetailSeriesContent
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId

@Composable
fun MetaDetailsScreen(
    type: String,
    id: String,
    onBack: () -> Unit,
    onPlay: ((type: String, videoId: String, parentMetaId: String, parentMetaType: String, title: String, logo: String?, poster: String?, background: String?, seasonNumber: Int?, episodeNumber: Int?, episodeTitle: String?, episodeThumbnail: String?, resumePositionMs: Long?) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val uiState by MetaDetailsRepository.uiState.collectAsStateWithLifecycle()
    val libraryUiState by remember {
        LibraryRepository.ensureLoaded()
        LibraryRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchProgressUiState by remember {
        WatchProgressRepository.ensureLoaded()
        WatchProgressRepository.uiState
    }.collectAsStateWithLifecycle()
    val screenAlpha = remember(type, id) { Animatable(0f) }
    val requestedMeta = uiState.meta?.takeIf { it.type == type && it.id == id }
    val needsFreshLoad = requestedMeta == null && !uiState.isLoading

    LaunchedEffect(type, id, needsFreshLoad) {
        if (!needsFreshLoad) {
            screenAlpha.snapTo(1f)
            return@LaunchedEffect
        }
        screenAlpha.snapTo(0f)
        screenAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 220),
        )
        MetaDetailsRepository.load(type, id)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(screenAlpha.value)
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            uiState.isLoading || (uiState.meta != null && requestedMeta == null) -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            uiState.errorMessage != null -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Failed to load",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = uiState.errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            requestedMeta != null -> {
                val meta = requestedMeta
                val isSaved = remember(libraryUiState.items, meta.id) {
                    libraryUiState.items.any { it.id == meta.id }
                }
                val movieProgress = watchProgressUiState.byVideoId[meta.id]
                val seriesResumeEntry = watchProgressUiState.entries
                    .filter { it.parentMetaId == meta.id }
                    .maxByOrNull { it.lastUpdatedEpochMs }
                val firstEpisode = remember(meta.videos) { meta.firstPlayableEpisode() }
                val playButtonLabel = remember(movieProgress, seriesResumeEntry, firstEpisode, meta.type) {
                    when {
                        meta.type == "series" && seriesResumeEntry != null ->
                            seriesResumeEntry.resumeLabel()
                        meta.type == "series" && firstEpisode != null ->
                            firstEpisode.playLabel()
                        meta.type != "series" && movieProgress != null ->
                            "Resume"
                        else -> "Play"
                    }
                }
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                ) {
                    DetailHero(meta = meta, scrollOffset = scrollState.value)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        DetailActionButtons(
                            playLabel = playButtonLabel,
                            saveLabel = if (isSaved) "Saved" else "Save",
                            onPlayClick = {
                                when {
                                    meta.type == "series" && seriesResumeEntry != null -> {
                                        onPlay?.invoke(
                                            meta.type,
                                            seriesResumeEntry.videoId,
                                            meta.id,
                                            meta.type,
                                            meta.name,
                                            meta.logo,
                                            meta.poster,
                                            meta.background,
                                            seriesResumeEntry.seasonNumber,
                                            seriesResumeEntry.episodeNumber,
                                            seriesResumeEntry.episodeTitle,
                                            seriesResumeEntry.episodeThumbnail,
                                            seriesResumeEntry.lastPositionMs,
                                        )
                                    }

                                    meta.type == "series" && firstEpisode != null -> {
                                        onPlay?.invoke(
                                            meta.type,
                                            buildPlaybackVideoId(
                                                parentMetaId = meta.id,
                                                seasonNumber = firstEpisode.season,
                                                episodeNumber = firstEpisode.episode,
                                                fallbackVideoId = firstEpisode.id,
                                            ),
                                            meta.id,
                                            meta.type,
                                            meta.name,
                                            meta.logo,
                                            meta.poster,
                                            meta.background,
                                            firstEpisode.season,
                                            firstEpisode.episode,
                                            firstEpisode.title,
                                            firstEpisode.thumbnail,
                                            null,
                                        )
                                    }

                                    else -> {
                                        onPlay?.invoke(
                                            meta.type,
                                            meta.id,
                                            meta.id,
                                            meta.type,
                                            meta.name,
                                            meta.logo,
                                            meta.poster,
                                            meta.background,
                                            null,
                                            null,
                                            null,
                                            null,
                                            movieProgress?.lastPositionMs,
                                        )
                                    }
                                }
                            },
                            onSaveClick = {
                                LibraryRepository.toggleSaved(
                                    meta.toLibraryItem(savedAtEpochMs = 0L),
                                )
                            },
                        )

                        DetailMetaInfo(meta = meta)

                        DetailCastSection(cast = meta.cast)

                        DetailSeriesContent(
                            meta = meta,
                            progressByVideoId = watchProgressUiState.byVideoId,
                            onEpisodeClick = { video ->
                                val season = video.season
                                val episode = video.episode
                                val playbackVideoId = buildPlaybackVideoId(
                                    parentMetaId = meta.id,
                                    seasonNumber = season,
                                    episodeNumber = episode,
                                    fallbackVideoId = video.id,
                                )
                                val savedProgress = watchProgressUiState.byVideoId[playbackVideoId]
                                onPlay?.invoke(
                                    meta.type,
                                    playbackVideoId,
                                    meta.id,
                                    meta.type,
                                    meta.name,
                                    meta.logo,
                                    meta.poster,
                                    meta.background,
                                    season,
                                    episode,
                                    video.title,
                                    video.thumbnail,
                                    savedProgress?.lastPositionMs,
                                )
                            },
                        )

                        Spacer(modifier = Modifier.height(32.dp + nuvioPlatformExtraBottomPadding))
                    }
                }
            }
        }

        // Back button overlay
        Box(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(start = 12.dp, top = 8.dp)
                .size(40.dp)
                .background(
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
                    shape = CircleShape,
                )
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

private fun MetaDetails.firstPlayableEpisode(): MetaVideo? =
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
        .firstOrNull()

private fun MetaVideo.playLabel(): String =
    if (season != null && episode != null) {
        "Play S${season}E${episode}"
    } else {
        "Play"
    }

private fun WatchProgressEntry.resumeLabel(): String =
    if (seasonNumber != null && episodeNumber != null) {
        "Resume S${seasonNumber}E${episodeNumber}"
    } else {
        "Resume"
    }
