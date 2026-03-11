package com.nuvio.app.features.streams

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.nuvioPlatformExtraBottomPadding
import kotlin.math.round

// ---------------------------------------------------------------------------
// Streams Screen
// ---------------------------------------------------------------------------

@Composable
fun StreamsScreen(
    type: String,
    videoId: String,
    title: String,
    logo: String? = null,
    poster: String? = null,
    background: String? = null,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    episodeTitle: String? = null,
    episodeThumbnail: String? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by StreamsRepository.uiState.collectAsStateWithLifecycle()
    val isEpisode = seasonNumber != null && episodeNumber != null

    LaunchedEffect(type, videoId) {
        StreamsRepository.load(type, videoId)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Background artwork
        val backdropUrl = background ?: poster
        if (backdropUrl != null) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(22.dp),
                contentScale = ContentScale.Crop,
            )
            // Dark scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.82f)),
            )
        }

        // Main content column
        Column(modifier = Modifier.fillMaxSize()) {
            // Hero block
            if (isEpisode && seasonNumber != null && episodeNumber != null) {
                EpisodeHeroBlock(
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    episodeTitle = episodeTitle ?: title,
                    thumbnail = episodeThumbnail ?: background ?: poster,
                    showTitle = title,
                )
            } else {
                MovieHeroBlock(
                    title = title,
                    logo = logo,
                )
            }

            // Provider filter chips
            ProviderFilterRow(
                groups = uiState.groups,
                selectedFilter = uiState.selectedFilter,
                onFilterSelected = { addonId -> StreamsRepository.selectFilter(addonId) },
            )

            // Stream list
            StreamList(
                uiState = uiState,
                modifier = Modifier.weight(1f),
            )
        }

        // Back button overlay (top-left)
        Box(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(start = 12.dp, top = 8.dp)
                .size(40.dp)
                .background(
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.45f),
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

// ---------------------------------------------------------------------------
// Movie Hero
// ---------------------------------------------------------------------------

@Composable
private fun MovieHeroBlock(
    title: String,
    logo: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null) {
            AsyncImage(
                model = logo,
                contentDescription = null,
                modifier = Modifier
                    .height(80.dp)
                    .fillMaxWidth(0.85f),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                text = title,
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Episode Hero
// ---------------------------------------------------------------------------

@Composable
private fun EpisodeHeroBlock(
    seasonNumber: Int,
    episodeNumber: Int,
    episodeTitle: String,
    thumbnail: String?,
    showTitle: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp),
    ) {
        // Thumbnail image
        if (thumbnail != null) {
            AsyncImage(
                model = thumbnail,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        // Gradient overlay bottom-up
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.5f),
                            Color.Black.copy(alpha = 0.9f),
                            Color.Black.copy(alpha = 0.98f),
                        ),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY,
                    ),
                ),
        )

        // Safe-area push-down for status bar, then content pinned to bottom
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            // Episode label
            Text(
                text = "S${seasonNumber} · E${episodeNumber}",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            // Episode title
            Text(
                text = episodeTitle,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            // Show title
            Text(
                text = showTitle,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Provider Filter Row
// ---------------------------------------------------------------------------

@Composable
private fun ProviderFilterRow(
    groups: List<AddonStreamGroup>,
    selectedFilter: String?,
    onFilterSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val addonGroups = groups.filter { it.streams.isNotEmpty() || it.isLoading }
    if (addonGroups.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // "All" chip
        FilterChip(
            label = "All",
            isSelected = selectedFilter == null,
            onClick = { onFilterSelected(null) },
        )
        addonGroups.forEach { group ->
            FilterChip(
                label = group.addonName,
                isSelected = selectedFilter == group.addonId,
                onClick = { onFilterSelected(group.addonId) },
            )
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                letterSpacing = 0.1.sp,
            ),
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// Stream List
// ---------------------------------------------------------------------------

@Composable
private fun StreamList(
    uiState: StreamsUiState,
    modifier: Modifier = Modifier,
) {
    val filteredGroups = uiState.filteredGroups
    val hasAnyStreams = filteredGroups.any { it.streams.isNotEmpty() }
    val allLoading = filteredGroups.all { it.isLoading }
    val anyLoading = filteredGroups.any { it.isLoading }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            horizontal = 12.dp,
            vertical = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        when {
            allLoading && !hasAnyStreams -> {
                item {
                    LoadingStateBlock()
                }
            }

            !hasAnyStreams && !anyLoading -> {
                item {
                    EmptyStateBlock()
                }
            }

            else -> {
                filteredGroups.forEach { group ->
                    streamSection(group = group, showHeader = uiState.selectedFilter == null)
                }
                if (anyLoading) {
                    item {
                        FooterLoadingBlock()
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(nuvioPlatformExtraBottomPadding + 80.dp))
                }
            }
        }
    }
}

private fun LazyListScope.streamSection(
    group: AddonStreamGroup,
    showHeader: Boolean,
) {
    if (group.streams.isEmpty() && !group.isLoading) return

    if (showHeader) {
        item(key = "header_${group.addonId}") {
            StreamSectionHeader(
                addonName = group.addonName,
                isLoading = group.isLoading,
            )
        }
    }

    items(
        items = group.streams,
        key = { stream -> "${group.addonId}_${stream.url ?: stream.infoHash ?: stream.streamLabel}" },
    ) { stream ->
        StreamCard(stream = stream)
        Spacer(modifier = Modifier.height(10.dp))
    }
}

// ---------------------------------------------------------------------------
// Stream Section Header
// ---------------------------------------------------------------------------

@Composable
private fun StreamSectionHeader(
    addonName: String,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = addonName,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
        )
        AnimatedVisibility(visible = isLoading, enter = fadeIn(), exit = fadeOut()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Fetching…",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Stream Card
// ---------------------------------------------------------------------------

@Composable
private fun StreamCard(
    stream: StreamItem,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stream.streamLabel,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 20.sp,
                    letterSpacing = 0.1.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            val subtitle = stream.streamSubtitle
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StreamSourceBadge(stream = stream)
                StreamFileSizeBadge(stream = stream)
            }
        }
    }
}

@Composable
private fun StreamSourceBadge(stream: StreamItem) {
    val (label, color) = when {
        stream.url != null -> "HTTP" to MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
        stream.infoHash != null -> "TORRENT" to Color(0xFF4CAF50).copy(alpha = 0.9f)
        stream.externalUrl != null -> "EXTERNAL" to MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)
        else -> return
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp,
            ),
            color = color,
        )
    }
}

@Composable
private fun StreamFileSizeBadge(stream: StreamItem) {
    val bytes = stream.behaviorHints.videoSize ?: return
    val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    val sizeLabel = if (gib >= 1.0) {
        val roundedGiB = round(gib * 10.0) / 10.0
        "$roundedGiB GB"
    } else {
        val mib = bytes.toDouble() / (1024.0 * 1024.0)
        "${round(mib).toInt()} MB"
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = "SIZE $sizeLabel",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp,
            ),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

// ---------------------------------------------------------------------------
// State blocks
// ---------------------------------------------------------------------------

@Composable
private fun LoadingStateBlock(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = "Finding streams…",
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun EmptyStateBlock(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.SearchOff,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "No streams found",
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "None of your installed addons returned streams for this title.",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FooterLoadingBlock(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Checking more addons…",
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
