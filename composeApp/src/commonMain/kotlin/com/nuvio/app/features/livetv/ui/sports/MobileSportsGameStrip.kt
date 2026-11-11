package com.nuvio.app.features.livetv.ui.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.sports.ChannelMatch
import com.nuvio.app.features.livetv.sports.Sport
import com.nuvio.app.features.livetv.sports.SportsCatalog
import com.nuvio.app.features.livetv.sports.SportsWatchGame
import com.nuvio.app.features.livetv.sports.SportsWatchRow

/** Touch controls over the video: select a pane, choose a game, then choose its broadcast. */
@Composable
internal fun MobileSportsGameStrip(
    games: List<SportsWatchGame>,
    rows: List<SportsWatchRow>,
    selectedEventId: String?,
    paneChannelKeys: List<String?>,
    focusedPane: Int,
    paneCount: Int,
    hideScores: Boolean,
    onGame: (SportsWatchGame) -> Boolean,
    onStream: (SportsWatchGame, String) -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onPane: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val chosenGames = remember { mutableStateMapOf<Int, String>() }
    val rowListState = rememberLazyListState()
    val rowHorizontalStates = remember { mutableMapOf<String, LazyListState>() }
    val streamListState = rememberLazyListState()
    var streamsOpen by remember { mutableStateOf(false) }
    var previousPane by remember { mutableIntStateOf(focusedPane) }
    var streamBinding by remember { mutableStateOf<Pair<Int, String?>?>(null) }
    val visiblePanes = paneCount.coerceIn(1, 4)
    val target = focusedPane.coerceIn(0, visiblePanes - 1)
    val currentKey = paneChannelKeys.getOrNull(target)
    val chosenId = chosenGames[target]
    val targetGameId = chosenId ?: selectedEventId
    val streamGame = games.firstOrNull { it.event.id == targetGameId }
        ?: if (chosenId == null) games.firstOrNull { game -> game.channels.any { it.channel.key == currentKey } }
        else null
    val streams = streamGame?.channels.orEmpty().distinctBy { it.channel.key }
    val use24h = use24h()
    val now = LiveTvClock.nowMs()

    LaunchedEffect(target) {
        if (previousPane != target) { previousPane = target; streamsOpen = false }
    }
    LaunchedEffect(streamGame?.event?.id, streamsOpen) {
        if (streamsOpen && streamGame == null) streamsOpen = false
    }
    LaunchedEffect(streamsOpen, target, streamGame?.event?.id, streams.map { it.channel.key }) {
        val binding = target to streamGame?.event?.id
        if (!streamsOpen) streamBinding = null
        else if (streams.isNotEmpty() && streamBinding != binding) {
            streamListState.scrollToItem(streams.indexOfFirst { it.channel.key == currentKey }.takeIf { it >= 0 } ?: 0)
            streamBinding = binding
        }
    }
    PlatformBackHandler(enabled = true) { if (streamsOpen) streamsOpen = false else onClose() }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val compact = maxWidth > maxHeight || maxHeight < 300.dp
        val panelHeight = minOf(maxHeight, if (compact) 224.dp else 320.dp)
        Column(
            Modifier.fillMaxWidth().height(panelHeight)
                .clip(tokens.shapes.card)
                .background(tokens.colors.background.copy(alpha = 0.97f))
                .border(tokens.borders.thin, tokens.colors.borderDefault, tokens.shapes.card)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).padding(start = NuvioTokens.Space.s12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Pane", style = MaterialTheme.typography.labelLarge, color = tokens.colors.textPrimary)
                    for (pane in 0 until visiblePanes) {
                        Chip("${pane + 1}", selected = pane == target, onClick = { onPane(pane); streamsOpen = false })
                    }
                }
                IconButton(onClick = onAdd, enabled = paneCount < 4) {
                    Icon(Icons.Filled.Add, "Add pane", tint = if (paneCount < 4) tokens.colors.textPrimary else tokens.colors.textDisabled)
                }
                IconButton(
                    enabled = paneCount > 1,
                    onClick = {
                        val remaining = chosenGames.entries
                            .filter { it.key in 0 until visiblePanes && it.key != target }
                            .associate { (pane, id) -> (if (pane > target) pane - 1 else pane) to id }
                        chosenGames.clear()
                        chosenGames.putAll(remaining)
                        streamsOpen = false
                        onRemove()
                    },
                ) {
                    Icon(Icons.Filled.DeleteOutline, "Remove selected pane", tint = if (paneCount > 1) tokens.colors.textPrimary else tokens.colors.textDisabled)
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close Sports controls", tint = tokens.colors.textPrimary) }
            }
            Row(
                Modifier.fillMaxWidth().height(44.dp).padding(horizontal = NuvioTokens.Space.s12),
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chip("Games", !streamsOpen, onClick = { streamsOpen = false })
                Chip("Streams", streamsOpen, enabled = streamGame != null, onClick = { streamsOpen = true })
                Text(streamGame?.event?.title ?: "Choose a game", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (streamsOpen && streamGame != null) {
                if (streams.isEmpty()) {
                    EmptySportsPicker("No streams available yet", "Choose another game or check its broadcasts on the Sports page.", Modifier.weight(1f))
                } else LazyRow(
                    Modifier.fillMaxWidth().weight(1f),
                    state = streamListState,
                    contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s8),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                ) {
                    items(streams, key = { it.channel.key }) { match ->
                        MobileSportsStreamCard(match, playing = match.channel.key == currentKey, compact = compact) {
                            onStream(streamGame, match.channel.key)
                        }
                    }
                }
            } else if (rows.isEmpty()) {
                EmptySportsPicker("No live games available", "Your Sports rows appear here when games are live or about to start.", Modifier.weight(1f))
            } else LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                state = rowListState,
                contentPadding = PaddingValues(bottom = NuvioTokens.Space.s8),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            ) {
                items(rows, key = { it.key }) { row ->
                    Column {
                        Text(row.title, style = MaterialTheme.typography.labelLarge, color = tokens.colors.textPrimary,
                            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s4))
                        LazyRow(
                            state = rowHorizontalStates.getOrPut(row.key) { LazyListState() },
                            contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s12),
                            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                        ) {
                            items(row.games, key = { it.event.id }) { game ->
                                MobileSportsGameCard(game, game.event.id == targetGameId, hideScores, now, use24h) {
                                    val selectedPane = target
                                    if (onGame(game)) {
                                        chosenGames[selectedPane] = game.event.id
                                        streamsOpen = true
                                    } else NuvioToastController.show("No available broadcast for this game right now.")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptySportsPicker(title: String, detail: String, modifier: Modifier) {
    val tokens = MaterialTheme.nuvio
    Column(modifier.fillMaxWidth().padding(NuvioTokens.Space.s16), verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
    }
}

@Composable
private fun MobileSportsGameCard(
    game: SportsWatchGame,
    selected: Boolean,
    hideScores: Boolean,
    now: Long,
    use24h: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val event = game.event
    val league = SportsCatalog.league(event.leagueId)
    val sides = event.ordered(league?.sport ?: Sport.OTHER)
    Column(
        Modifier.width(228.dp).heightIn(min = 110.dp).clip(tokens.shapes.compactCard)
            .background(tokens.colors.surfaceElevated)
            .border(if (selected) 2.dp else tokens.borders.thin,
                if (selected) tokens.colors.accent else tokens.colors.borderDefault, tokens.shapes.compactCard)
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s8, vertical = NuvioTokens.Space.s4),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
            LeagueMark(league, 16.dp)
            Text(league?.short.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted, maxLines = 1)
            Text(whenLabel(event, now, use24h, hideScores), style = MaterialTheme.typography.labelSmall,
                color = SportsLiveRed, maxLines = 1)
        }
        if (sides.size == 2) sides.forEach { side ->
            Row(Modifier.fillMaxWidth().background(teamBandBrush(side.team)).padding(horizontal = NuvioTokens.Space.s8, vertical = NuvioTokens.Space.s2),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
                TeamLogo(side.team, 20.dp)
                Text(side.team.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = androidx.compose.ui.graphics.Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (event.hasScore && !hideScores) Text(side.score.orEmpty(), style = MaterialTheme.typography.bodyMedium,
                    color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Black)
            }
        } else Text(eventHeadline(event, league), Modifier.padding(horizontal = NuvioTokens.Space.s8),
            style = MaterialTheme.typography.bodySmall, color = tokens.colors.textPrimary,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(game.channels.firstOrNull()?.channel?.name ?: "Check broadcasts", Modifier.padding(NuvioTokens.Space.s8),
            style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MobileSportsStreamCard(match: ChannelMatch, playing: Boolean, compact: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Box(
        Modifier.width(228.dp).fillMaxSize().clip(tokens.shapes.compactCard)
            .background(tokens.colors.surfaceElevated)
            .border(if (playing) 2.dp else tokens.borders.thin,
                if (playing) tokens.colors.accent else tokens.colors.borderDefault, tokens.shapes.compactCard)
            .clickable(onClick = onClick),
    ) {
        // Compact windows normally fit all fields in 112dp. Scroll unusually large text or
        // shorter windows inside the card rather than clipping the broadcast's details.
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(if (compact) NuvioTokens.Space.s8 else NuvioTokens.Space.s12),
            verticalArrangement = Arrangement.spacedBy(if (compact) NuvioTokens.Space.s4 else NuvioTokens.Space.s6),
        ) {
            if (playing || !match.quality.isNullOrBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6), verticalAlignment = Alignment.CenterVertically) {
                    if (playing) SportsBadge("PLAYING", tokens.colors.accent)
                    match.quality?.takeIf { it.isNotBlank() }?.let { SportsBadge(it, tokens.colors.surface) }
                }
            }
            Text(match.channel.name, style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (match.channel.sourceName.isNotBlank()) Text(match.channel.sourceName,
                style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (match.how.isNotBlank()) Text(if (match.sure) match.how else "Guess: ${match.how}",
                style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted,
                maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
