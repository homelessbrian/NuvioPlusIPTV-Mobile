package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.streams.StreamBadge
import com.nuvio.app.features.streams.StreamBadgeChipSize
import com.nuvio.app.features.streams.StreamBadgeImage
import com.nuvio.app.features.streams.StreamBadgeMatcher
import com.nuvio.app.features.streams.StreamBadgeSettingsRepository
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlayerScreenRuntime.rememberPlayerBadges(): List<StreamBadge> {
    val settings by remember {
        StreamBadgeSettingsRepository.ensureLoaded()
        StreamBadgeSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    if (!settings.showInPlayer) return emptyList()
    val controller = playerController
    LaunchedEffect(controller, activeSourceUrl, selectedAudioIndex, controlsVisible, playbackSnapshot.isLoading) {
        if (controller != null && controlsVisible && !playbackSnapshot.isLoading) {
            streamMediaInfo = controller.getMediaInfo()
        }
    }
    val track = audioTracks.firstOrNull { it.index == selectedAudioIndex }
    val languageName = track?.language?.let(::languageLabelResForCode)?.let { stringResource(it) }
    val text = playbackMatchText(streamMediaInfo, listOfNotNull(track?.language, languageName))
    // Keep candidates separate so regex lookaheads in one don't bleed into another.
    // Collapse whitespace so \n in stream names doesn't break single-line regex patterns.
    val playbackCandidate = text.takeIf { it.isNotBlank() }
    val metaCandidates = listOfNotNull(
        activeSourceFilename?.collapseWhitespace(),
        activeStreamTitle.collapseWhitespace()
    )
    return remember(playbackCandidate, metaCandidates, settings.rules, settings.playerBadgeSource) {
        val rules = settings.rules.forPlayer(settings.playerBadgeSource)
        val filters = StreamBadgeMatcher.compile(rules)
        val matched = linkedMapOf<String, StreamBadge>()
        fun collect(badge: StreamBadge) {
            val key = (badge.imageURL.takeIf { it.isNotBlank() } ?: badge.name).lowercase()
            if (key !in matched) matched[key] = badge
        }
        // Phase 1: match all filters against playback candidate (decoder truth)
        // and track which groups got a hit.
        val groupsMatchedByPlayback = mutableSetOf<String>()
        if (playbackCandidate != null) {
            for (filter in filters) {
                if (filter.regex.containsMatchIn(playbackCandidate)) {
                    collect(filter.badge)
                    if (filter.groupId.isNotBlank()) groupsMatchedByPlayback += filter.groupId
                }
            }
        }
        // Phase 2: match remaining filters against filename/streamName, but skip groups
        // that already matched from the decoder.
        if (metaCandidates.isNotEmpty()) {
            val metaFilters = if (groupsMatchedByPlayback.isEmpty()) {
                filters
            } else {
                filters.filter { it.groupId.isBlank() || it.groupId !in groupsMatchedByPlayback }
            }
            if (metaFilters.isNotEmpty()) {
                for (badge in StreamBadgeMatcher.matchedBadges(metaCandidates, metaFilters)) {
                    collect(badge)
                }
            }
        }
        matched.values.filter { it.imageURL.isNotBlank() }
    }
}

private fun String.collapseWhitespace(): String? =
    replace(Regex("\\s+"), " ").trim().takeIf { it.isNotBlank() }

@Composable
internal fun PlayerBadges(
    badges: List<StreamBadge>,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = { badges.forEach { StreamBadgeImage(badge = it, size = StreamBadgeChipSize.PLAYER) } },
        modifier = modifier,
    ) { measurables, constraints ->
        val gap = 4.dp.roundToPx()
        val placeables = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
        var width = 0
        var visibleCount = 0
        for (placeable in placeables) {
            val next = if (visibleCount == 0) placeable.width else width + gap + placeable.width
            if (next > constraints.maxWidth) break
            width = next
            visibleCount++
        }
        val visible = placeables.take(visibleCount)
        val height = visible.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            var x = 0
            visible.forEach { placeable ->
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width + gap
            }
        }
    }
}
