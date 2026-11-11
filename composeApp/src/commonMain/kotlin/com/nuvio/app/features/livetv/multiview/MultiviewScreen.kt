package com.nuvio.app.features.livetv.multiview

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.LocalScreenActive
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.data.VideoPlaying
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.sports.ChannelPickerSearchResult
import com.nuvio.app.features.livetv.sports.channelPickerSearchResults
import com.nuvio.app.features.livetv.sports.SportsSettings
import com.nuvio.app.features.livetv.sports.SportsWatchController
import com.nuvio.app.features.livetv.sports.SportsWatchGame
import com.nuvio.app.features.livetv.sports.SportsWatchRow
import com.nuvio.app.features.livetv.ui.LiveTvViewState
import com.nuvio.app.features.livetv.ui.liveTvSideInsets
import com.nuvio.app.features.livetv.ui.sports.MobileSportsGameStrip
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun MultiviewScreen(
    onBack: () -> Unit,
    session: MultiviewSession,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    sportsMode: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    if (!isMultiviewSupported) {
        Column(modifier.fillMaxSize().background(tokens.colors.background).statusBarsPadding()) {
            NuvioScreenHeader(title = "Multiview", onBack = onBack, includeStatusBarPadding = false)
            Text("Multiview is unavailable on this device.", color = tokens.colors.textPrimary, modifier = Modifier.padding(NuvioTokens.Space.s16))
        }
        return
    }
    val selection by session.selection.collectAsStateWithLifecycle()
    val sports = remember(session, sportsMode) { if (sportsMode) SportsWatchController(session.profileId) else null }
    DisposableEffect(sports) { onDispose { sports?.close() } }
    val noGames = remember { MutableStateFlow(emptyList<SportsWatchGame>()) }
    val noRows = remember { MutableStateFlow(emptyList<SportsWatchRow>()) }
    val noChannels = remember { MutableStateFlow(emptyList<LiveChannel>()) }
    val defaultSportsSettings = remember { MutableStateFlow(SportsSettings()) }
    val sportsGames by (sports?.games ?: noGames).collectAsStateWithLifecycle()
    val sportsRows by (sports?.rows ?: noRows).collectAsStateWithLifecycle()
    val sportsChannels by (sports?.allowedChannels ?: noChannels).collectAsStateWithLifecycle()
    val sportsSettings by (sports?.settings ?: defaultSportsSettings).collectAsStateWithLifecycle()
    val listFlow = remember { LiveTvRepository.ensureLoaded(); LiveTvViewState.list }
    val collectedList by listFlow.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // Lifecycle-aware collection can lag the first resumed frame. Resolve against the current
    // filtered value before recreating native connections or displaying stale picker rows.
    val list = if (lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) listFlow.value else collectedList
    // This is the same filtered list used by Live TV search: hidden and locked content is absent.
    val allowed = remember(list.visible, sportsChannels, sports, selection.channels) {
        if (sports == null) list.visible.associateBy { it.key }
        else {
            // A freshly seeded channel is checked synchronously while the eager permission
            // projection catches up; an empty first flow emission must not discard that seed.
            val current = sportsChannels.associateBy { it.key }
            current + selection.channels.filterNotNull().filter { it.key !in current && sports.mayPlay(it) }.associateBy { it.key }
        }
    }
    val permittedSelection = remember(selection, allowed) { selection.retainAllowed(allowed) }
    LaunchedEffect(session, allowed) { session.retainAllowed(allowed) }
    fun profileIsCurrent(): Boolean = session.isForProfile(
        ProfileRepository.state.value.activeProfile?.profileIndex ?: ProfileRepository.activeProfileId,
    )
    val playbackActive = active && LocalScreenActive.current && profileIsCurrent() && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val audio = remember(session) { MultiviewAudioCoordinator() }
    var editingPane by remember(session) { mutableStateOf<Int?>(null) }
    var sportsControlsOpen by remember(session) { mutableStateOf(sportsMode) }
    var sportsTargetPane by remember(session) {
        mutableIntStateOf((0 until selection.layout.paneCount).firstOrNull { selection.channels[it] == null }
            ?: selection.focusedPane ?: 0)
    }
    val sportsEvents = remember(session) { mutableStateMapOf<Int, String>() }
    val playerPermission = remember(sports) { sports?.let { watch -> { channel: LiveChannel -> watch.mayPlay(channel) } } }

    fun openSportsPane(pane: Int) {
        sportsTargetPane = pane
        sportsControlsOpen = true
    }
    PlatformBackHandler(enabled = sportsMode && sportsControlsOpen) { sportsControlsOpen = false }

    LaunchedEffect(audio, playbackActive, permittedSelection.focusedOwnerId) {
        audio.focus(permittedSelection.focusedOwnerId.takeIf { playbackActive })
    }
    if (playbackActive) {
        DisposableEffect(audio) {
            VideoPlaying.opened()
            onDispose {
                audio.muteAll()
                VideoPlaying.closed()
            }
        }
    }

    Column(
        modifier.fillMaxSize().background(tokens.colors.background).statusBarsPadding()
            .liveTvSideInsets().padding(bottom = nuvioSafeBottomPadding()),
    ) {
        NuvioScreenHeader(
            title = if (sportsMode) "Sports Multiview" else "Multiview",
            onBack = onBack,
            includeStatusBarPadding = false,
            actions = {
                IconButton(onClick = {
                    if (sportsMode) sportsControlsOpen = true else editingPane = permittedSelection.focusedPane ?: 0
                }) {
                    Icon(Icons.Filled.Tune, contentDescription = "Edit streams and layout", tint = tokens.colors.textPrimary)
                }
            },
        )
        Text(
            if (sportsMode) "Tap a pane, choose a game, then choose a stream."
            else "Tap a video to hear it. Hold a pane to change its channel.",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s4),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(NuvioTokens.Space.s4)) {
                val layoutRows = permittedSelection.layout.rows
                for (pane in 0 until permittedSelection.layout.paneCount) {
                    val rowIndex = layoutRows.indexOfFirst { pane in it }
                    val row = layoutRows[rowIndex]
                    val columnIndex = row.indexOf(pane)
                    val paneWidth = maxWidth / row.size
                    val paneHeight = maxHeight / layoutRows.size
                    key(permittedSelection.paneIds[pane]) {
                        val channel = permittedSelection.channels[pane]
                        MultiviewPane(
                            ownerId = permittedSelection.paneIds[pane],
                            pane = pane,
                            channel = channel,
                            focused = permittedSelection.focusedPane == pane,
                            playbackActive = playbackActive,
                            audio = audio,
                            mayPlayChannel = playerPermission,
                            onFocus = {
                                if (sportsMode) openSportsPane(pane) else {
                                    session.focus(pane)
                                    // Re-selecting the same pane retries lost system audio focus.
                                    audio.focus(permittedSelection.paneIds[pane])
                                }
                            },
                            onEdit = { if (sportsMode) openSportsPane(pane) else editingPane = pane },
                            modifier = Modifier.offset(x = paneWidth * columnIndex, y = paneHeight * rowIndex)
                                .width(paneWidth).height(paneHeight).padding(NuvioTokens.Space.s4),
                        )
                    }
                }
            }
            if (sportsMode && sportsControlsOpen && sports != null) {
            val target = sportsTargetPane.coerceIn(0, permittedSelection.layout.paneCount - 1)
            MobileSportsGameStrip(
                games = sportsGames,
                rows = sportsRows,
                selectedEventId = sportsEvents[target] ?: sportsGames.firstOrNull { game ->
                    game.channels.any { it.channel.key == permittedSelection.channels[target]?.key }
                }?.event?.id,
                paneChannelKeys = permittedSelection.channels.take(permittedSelection.layout.paneCount).map { it?.key },
                focusedPane = target,
                paneCount = permittedSelection.layout.paneCount,
                hideScores = sportsSettings.hideScores,
                onGame = { game -> sports.openGame(game.event.id) },
                onStream = { game, channelKey ->
                    val pane = target
                    val current = sports.resolve(game.event.id, channelKey)
                    if (profileIsCurrent() && current != null) {
                        sportsEvents[pane] = game.event.id
                        session.assign(pane, current.channel)
                        session.focus(pane)
                        audio.focus(session.selection.value.paneIds[pane])
                    } else NuvioToastController.show("This broadcast is no longer available.")
                },
                onAdd = {
                    val count = session.selection.value.layout.paneCount
                    if (count < 4) {
                        session.setLayout(MultiviewLayout.entries.first { it.paneCount == count + 1 })
                        sportsTargetPane = count
                    }
                },
                onRemove = {
                    val count = session.selection.value.layout.paneCount
                    if (count > 1) {
                        val remaining = sportsEvents.entries.filter { it.key in 0 until count && it.key != target }
                            .associate { (pane, eventId) -> (if (pane > target) pane - 1 else pane) to eventId }
                        sportsEvents.clear()
                        sportsEvents.putAll(remaining)
                        session.removePane(target)
                        sportsTargetPane = target.coerceAtMost(count - 2)
                    }
                },
                onPane = ::openSportsPane,
                onClose = { sportsControlsOpen = false },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            }
        }
    }
    editingPane?.let { requestedPane ->
        if (playbackActive) {
            MultiviewControlsSheet(
                selection = permittedSelection,
                initialPane = requestedPane.coerceAtMost(permittedSelection.layout.paneCount - 1),
                channels = list.visible,
                onLayout = session::setLayout,
                onAssign = { pane, channel ->
                    // Recheck the current list so a parental/profile update cannot commit a stale row.
                    val current = LiveTvViewState.list.value.visible.firstOrNull { it.key == channel.key }
                    if (profileIsCurrent() && current != null) session.assign(pane, current)
                    editingPane = null
                },
                onClear = { session.assign(it, null) },
                onDismiss = { editingPane = null },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MultiviewPane(
    ownerId: Int,
    pane: Int,
    channel: LiveChannel?,
    focused: Boolean,
    playbackActive: Boolean,
    audio: MultiviewAudioCoordinator,
    mayPlayChannel: ((LiveChannel) -> Boolean)?,
    onFocus: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier,
) {
    val tokens = MaterialTheme.nuvio
    var attempt by remember(ownerId, channel) { mutableIntStateOf(0) }
    Box(
        modifier.clip(tokens.shapes.compactCard).background(tokens.colors.surfaceCard)
            .border(if (focused) 2.dp else 1.dp, if (focused) tokens.colors.accent else tokens.colors.borderDefault, tokens.shapes.compactCard),
    ) {
        if (channel != null && playbackActive) {
            // A replacement gets a new native owner; disposing the old pane closes its connection.
            key(ownerId, channel, attempt) {
                val player = rememberMultiviewPlayer(mayPlayChannel)
                val state by player.state.collectAsStateWithLifecycle()
                PlatformMultiviewSurface(player, Modifier.fillMaxSize())
                DisposableEffect(player, channel) {
                    player.setMuted(true)
                    player.play(channel)
                    onDispose {
                        player.setPaused(true)
                        player.release()
                    }
                }
                // Sound follows the native owner when its visible slot is compacted.
                DisposableEffect(player, audio, ownerId) {
                    audio.register(ownerId, player)
                    onDispose { audio.unregister(ownerId, player) }
                }
                if (state.isLoading) NuvioLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                if (state.error != null) {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(NuvioTokens.Space.s12).zIndex(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(state.error ?: "Unable to play this channel", color = tokens.colors.textPrimary, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { attempt++ }) { Text("Retry", color = tokens.colors.accent) }
                    }
                }
            }
        }
        // The shared touch layer also keeps native video views from consuming selection gestures.
        Box(
            Modifier.fillMaxSize().combinedClickable(
                onClick = { if (channel == null) onEdit() else onFocus() },
                onLongClick = onEdit,
            ).semantics {
                contentDescription = "Pane ${pane + 1}, ${channel?.name ?: "add channel"}${if (focused) ", audio selected" else ""}"
                selected = focused
            },
            contentAlignment = Alignment.Center,
        ) {
            if (channel == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = tokens.colors.accent)
                    Text("Add channel", color = tokens.colors.textPrimary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().background(tokens.colors.background.copy(alpha = 0.82f))
                .padding(start = NuvioTokens.Space.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                channel?.name ?: "Pane ${pane + 1}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (focused) Icon(Icons.Filled.VolumeUp, contentDescription = "Audio selected", tint = tokens.colors.accent)
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Change pane ${pane + 1}", tint = tokens.colors.textPrimary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MultiviewControlsSheet(
    selection: MultiviewSelection,
    initialPane: Int,
    channels: List<LiveChannel>,
    onLayout: (MultiviewLayout) -> Unit,
    onAssign: (Int, LiveChannel) -> Unit,
    onClear: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var targetPane by remember { mutableStateOf(initialPane) }
    var query by remember { mutableStateOf("") }
    val target = targetPane.coerceAtMost(selection.layout.paneCount - 1)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val result by produceState(ChannelPickerSearchResult<LiveChannel>("", emptyList()), channels) {
        channelPickerSearchResults(channels, snapshotFlow { query }) {
            "${it.name} ${it.number} ${it.group}"
        }.collect { value = it }
    }
    val matches = result.forQuery(query)

    NuvioModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        showDragHandle = false,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Short windows use one scrollable control row, leaving the remaining height to
            // channels. At 360dp: 40 header + ~48 controls + 52 search + 1 divider + 20 shell.
            val singleControlRow = maxWidth >= 560.dp || maxHeight < 480.dp
            // With an IME-reduced 180dp window, merge the header into that same toolbar:
            // The merged toolbar also removes outer search spacing to preserve a full row.
            val mergedToolbar = maxHeight < 240.dp
            val actions: @Composable () -> Unit = {
                IconButton(onClick = { onClear(target) }, enabled = selection.channels[target] != null, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Clear pane ${target + 1}", tint = if (selection.channels[target] == null) tokens.colors.textDisabled else tokens.colors.textPrimary)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close Multiview controls", tint = tokens.colors.textPrimary)
                }
            }
            Column(Modifier.fillMaxSize()) {
                if (mergedToolbar) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            MultiviewPickerControls(selection, target, singleRow = true, onTarget = { targetPane = it }, onLayout = onLayout, showTitle = true)
                        }
                        actions()
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = NuvioTokens.Space.s12),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Multiview", style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${target + 1} · ${selection.channels[target]?.name ?: "Empty pane"}",
                            modifier = Modifier.weight(1f).padding(start = NuvioTokens.Space.s12),
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        actions()
                    }
                    MultiviewPickerControls(selection, target, singleRow = singleControlRow, onTarget = { targetPane = it }, onLayout = onLayout)
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s12, vertical = if (mergedToolbar) 0.dp else NuvioTokens.Space.s4)) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = tokens.colors.textPrimary),
                        cursorBrush = SolidColor(tokens.colors.accent),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(tokens.shapes.compactCard)
                            .background(tokens.colors.surfaceCard)
                            .border(tokens.borders.thin, tokens.colors.borderDefault, tokens.shapes.compactCard)
                            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s8)
                            .semantics { contentDescription = "Search channels for pane ${target + 1}" },
                        decorationBox = { field ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) Text("Search channels for pane ${target + 1}", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
                                field()
                            }
                        },
                    )
                }
                NuvioBottomSheetDivider()
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    if (matches.isEmpty()) {
                        item { Text(if (channels.isEmpty()) "No available channels" else "No matching channels", color = tokens.colors.textMuted, modifier = Modifier.padding(NuvioTokens.Space.s16)) }
                    }
                    items(matches, key = { it.key }) { channel ->
                        NuvioBottomSheetActionRow(
                            title = channel.name,
                            selected = selection.channels[target]?.key == channel.key,
                            onClick = { onAssign(target, channel) },
                            trailingContent = { Text(channel.group, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, modifier = Modifier.fillMaxWidth(0.35f)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MultiviewPickerControls(
    selection: MultiviewSelection,
    target: Int,
    singleRow: Boolean,
    onTarget: (Int) -> Unit,
    onLayout: (MultiviewLayout) -> Unit,
    showTitle: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    val layouts: @Composable () -> Unit = {
        Text("Layout", style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted)
        MultiviewLayout.entries.filter { it.paneCount > 1 }.forEach { layout ->
            Chip(layout.title, selected = selection.layout == layout, onClick = { onLayout(layout) })
        }
    }
    val panes: @Composable () -> Unit = {
        Text("Pane", style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted)
        repeat(selection.layout.paneCount) { pane ->
            Chip("${pane + 1}", selected = target == pane, onClick = { onTarget(pane) })
        }
    }
    if (singleRow) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).heightIn(min = 40.dp).padding(horizontal = NuvioTokens.Space.s12),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showTitle) Text("Multiview", style = MaterialTheme.typography.labelLarge, color = tokens.colors.textPrimary)
            layouts()
            Spacer(Modifier.width(NuvioTokens.Space.s8))
            panes()
        }
    } else {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).heightIn(min = 40.dp).padding(horizontal = NuvioTokens.Space.s12),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) { layouts() }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).heightIn(min = 40.dp).padding(horizontal = NuvioTokens.Space.s12),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) { panes() }
    }
}
