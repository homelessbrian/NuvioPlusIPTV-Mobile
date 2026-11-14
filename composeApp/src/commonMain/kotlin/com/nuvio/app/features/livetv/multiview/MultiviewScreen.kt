package com.nuvio.app.features.livetv.multiview

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.SportsSoccer
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
import kotlinx.coroutines.delay
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
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.data.VideoPlaying
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.player.LiveTvPlayback
import com.nuvio.app.features.livetv.sports.ChannelMatch
import com.nuvio.app.features.livetv.sports.ChannelPickerSearchResult
import com.nuvio.app.features.livetv.sports.channelPickerSearchResults
import com.nuvio.app.features.livetv.sports.SportsRepository
import com.nuvio.app.features.livetv.sports.SportsSettings
import com.nuvio.app.features.livetv.sports.SportsWatchController
import com.nuvio.app.features.livetv.sports.SportsWatchGame
import com.nuvio.app.features.livetv.sports.SportsWatchRow
import com.nuvio.app.features.livetv.ui.ChannelLogo
import com.nuvio.app.features.livetv.ui.LiveTvViewState
import com.nuvio.app.features.livetv.ui.liveTvSideInsets
import com.nuvio.app.features.livetv.ui.sports.MobileSportsGameStrip
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * One Multiview for everything: the channel picker (with your groups) and the Sports games are
 * two ways of filling the same panes. [startOnSports] opens it on the games (from the Sports
 * section). Channels set aside with "Add to Multiview" or the player's Multiview button are put
 * in the panes when it opens. Selections live only in memory; no stream links are saved.
 */
@Composable
fun MultiviewScreen(
    onBack: () -> Unit,
    session: MultiviewSession,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    startOnSports: Boolean = false,
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
    // The Sports games come from the Sports watch model, made only once the Sports picker is used
    // (so plain channel Multiview doesn't pay for it).
    var sportsUsed by remember(session) { mutableStateOf(startOnSports) }
    var showingSports by remember(session) { mutableStateOf(startOnSports) }
    val sports = remember(session, sportsUsed) { if (sportsUsed) SportsWatchController(session.profileId) else null }
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
    // Sports adds the channels its games use (its own sources), checked by its own rules.
    val allowed = remember(list.visible, sportsChannels, sports, selection.channels) {
        val base = list.visible.associateBy { it.key }
        if (sports == null) base
        else {
            // A freshly seeded channel is checked synchronously while the eager permission
            // projection catches up; an empty first flow emission must not discard that seed.
            val current = base + sportsChannels.associateBy { it.key }
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
    var sportsControlsOpen by remember(session) { mutableStateOf(startOnSports) }
    var sportsTargetPane by remember(session) {
        mutableIntStateOf((0 until selection.layout.paneCount).firstOrNull { selection.channels[it] == null }
            ?: selection.focusedPane ?: 0)
    }
    val sportsEvents = remember(session) { mutableStateMapOf<Int, String>() }
    var pickerGroup by remember(session) { mutableStateOf<String?>(null) }
    val sportsShown = SportsRepository.settings.collectAsStateWithLifecycle().value.showInSidebar
    val playerPermission = remember(sports) {
        sports?.let { watch ->
            { channel: LiveChannel ->
                profileIsCurrent() && (LiveTvViewState.list.value.visible.any { it == channel } || watch.mayPlay(channel))
            }
        }
    }

    // Channels set aside with "Add to Multiview" (or the player's button) go into the panes.
    val queued = remember(session) { MultiviewQueue.take() }
    var seeded by remember(session) { mutableStateOf(queued.isEmpty()) }
    LaunchedEffect(session, list.visible) {
        if (seeded || list.visible.isEmpty()) return@LaunchedEffect
        seeded = true
        val byKey = list.visible.associateBy { it.key }
        val chosen = queued.mapNotNull { byKey[it] }
        if (chosen.isEmpty() || !profileIsCurrent()) return@LaunchedEffect
        val firstPane = session.placeQueued(chosen) ?: return@LaunchedEffect
        // The first one (the channel you were watching) keeps the sound.
        session.focus(firstPane)
        audio.focus(session.selection.value.paneIds[firstPane])
        if (queued.size > chosen.size) NuvioToastController.show("Some channels you added aren't available on this profile.")
    }

    // Now-showing titles for the pane labels, refreshed each minute from the guide in memory.
    val paneKeys = permittedSelection.channels.take(permittedSelection.layout.paneCount).mapNotNull { it?.key }
    val nowShowing by produceState(emptyMap<String, String>(), paneKeys) {
        while (true) {
            value = LiveTvRepository.nowNext(paneKeys).mapNotNull { (key, nn) -> nn.now?.title?.let { key to it } }.toMap()
            kotlinx.coroutines.delay(60_000L)
        }
    }

    fun openSportsPane(pane: Int) {
        sportsTargetPane = pane
        sportsControlsOpen = true
    }
    fun openPicker(pane: Int) {
        if (showingSports) openSportsPane(pane) else editingPane = pane
    }
    fun showSports(pane: Int) {
        editingPane = null
        sportsUsed = true
        showingSports = true
        openSportsPane(pane)
    }
    fun showChannels(pane: Int) {
        sportsControlsOpen = false
        showingSports = false
        editingPane = pane
    }
    fun playMatch(pane: Int, eventId: String, channelKey: String?): Boolean {
        val watch = sports ?: return false
        val current = watch.resolve(eventId, channelKey) ?: return false
        if (!profileIsCurrent()) return false
        sportsEvents[pane] = eventId
        session.assign(pane, current.channel)
        session.focus(pane)
        audio.focus(session.selection.value.paneIds[pane])
        return true
    }
    /** The game playing in this pane and its streams after the current one (the next to try). */
    fun nextStreams(pane: Int): Pair<String, List<ChannelMatch>>? {
        val channelKey = permittedSelection.channels.getOrNull(pane)?.key ?: return null
        val eventId = sportsEvents[pane]
            ?: sportsGames.firstOrNull { game -> game.channels.any { it.channel.key == channelKey } }?.event?.id
            ?: return null
        val game = sportsGames.firstOrNull { it.event.id == eventId } ?: return null
        val streams = game.channels.distinctBy { it.channel.key }
        val at = streams.indexOfFirst { it.channel.key == channelKey }
        return eventId to streams.drop(at + 1)
    }
    /**
     * A pane whose channel won't play (out of reconnect tries): the game's next stream (a game
     * pane), else a copy of the channel from another playlist. Follows the "Backup channel"
     * setting like the full-screen player; the pane keeps its sound choice.
     */
    fun switchToBackup(pane: Int, failed: LiveChannel, tried: Set<String>): Boolean {
        if (!profileIsCurrent()) return false
        nextStreams(pane)?.let { (eventId, candidates) ->
            if (LiveTvPreferences.currentSettings().autoBackupChannel) {
                val watch = sports
                val next = candidates.asSequence().filter { it.channel.key !in tried }
                    .mapNotNull { watch?.resolve(eventId, it.channel.key)?.channel }
                    .firstOrNull { it.key !in tried && playerPermission?.invoke(it) != false }
                if (next != null) {
                    sportsEvents[pane] = eventId
                    session.assign(pane, next)
                    return true
                }
            }
        }
        val backup = LiveTvPlayback.backupChannel(failed, tried)
            ?.takeIf { c -> playerPermission?.invoke(c) ?: LiveTvViewState.list.value.visible.any { it == c } } ?: return false
        session.assign(pane, backup)
        return true
    }
    PlatformBackHandler(enabled = sportsControlsOpen) { sportsControlsOpen = false }

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
            title = "Multiview",
            onBack = onBack,
            includeStatusBarPadding = false,
            actions = {
                IconButton(onClick = { openPicker(permittedSelection.focusedPane ?: sportsTargetPane.takeIf { showingSports } ?: 0) }) {
                    Icon(Icons.Filled.Tune, contentDescription = "Edit streams and layout", tint = tokens.colors.textPrimary)
                }
            },
        )
        Text(
            if (showingSports) "Tap a pane, then tap a game to play it there. Hold a game for its other streams."
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
                            nowTitle = channel?.key?.let { nowShowing[it] },
                            focused = permittedSelection.focusedPane == pane,
                            playbackActive = playbackActive,
                            audio = audio,
                            mayPlayChannel = playerPermission,
                            // A game stream that failed: the button tries the game's next stream.
                            nextStream = { nextStreams(pane)?.second?.firstOrNull()?.channel?.name },
                            onNextStream = {
                                val (eventId, candidates) = nextStreams(pane) ?: return@MultiviewPane
                                if (candidates.none { playMatch(pane, eventId, it.channel.key) }) {
                                    NuvioToastController.show("No other stream for this game right now.")
                                }
                            },
                            onFocus = {
                                session.focus(pane)
                                // Re-selecting the same pane retries lost system audio focus.
                                audio.focus(permittedSelection.paneIds[pane])
                                // With the games open, a tap also picks the pane they fill.
                                if (showingSports && sportsControlsOpen) openSportsPane(pane)
                            },
                            onEdit = { openPicker(pane) },
                            onBackup = { failed, tried -> switchToBackup(pane, failed, tried) },
                            modifier = Modifier.offset(x = paneWidth * columnIndex, y = paneHeight * rowIndex)
                                .width(paneWidth).height(paneHeight).padding(NuvioTokens.Space.s4),
                        )
                    }
                }
            }
            if (showingSports && sportsControlsOpen && sports != null) {
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
                onPlayGame = { game ->
                    sports.openGame(game.event.id)
                    playMatch(target, game.event.id, null)
                },
                onStream = { game, channelKey ->
                    if (!playMatch(target, game.event.id, channelKey)) NuvioToastController.show("This broadcast is no longer available.")
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
                onChannels = { showChannels(target) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            }
        }
    }
    editingPane?.let { requestedPane ->
        if (playbackActive) {
            // Your groups, as the Live TV list shows them (Favorites and Recently watched first).
            val groups = remember(list.sections) {
                list.sections.filter { !it.group.locked && it.channels.isNotEmpty() }
                    .map { MultiviewPickerGroup(it.group.id, it.group.title, it.channels) }
            }
            MultiviewControlsSheet(
                selection = permittedSelection,
                initialPane = requestedPane.coerceAtMost(permittedSelection.layout.paneCount - 1),
                channels = list.visible,
                groups = groups,
                group = pickerGroup?.takeIf { id -> groups.any { it.id == id } },
                onGroup = { pickerGroup = it },
                onSports = if (sportsShown) ({ pane: Int -> showSports(pane) }) else null,
                onLayout = session::setLayout,
                onAssign = { pane, channel ->
                    // Recheck the current list so a parental/profile update cannot commit a stale row.
                    val current = LiveTvViewState.list.value.visible.firstOrNull { it.key == channel.key }
                    if (profileIsCurrent() && current != null) {
                        sportsEvents.remove(pane)
                        session.assign(pane, current)
                    }
                    editingPane = null
                },
                onClear = { sportsEvents.remove(it); session.assign(it, null) },
                onDismiss = { editingPane = null },
            )
        }
    }
}

/** A group in the Multiview channel picker. */
internal data class MultiviewPickerGroup(val id: String, val title: String, val channels: List<LiveChannel>)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MultiviewPane(
    ownerId: Int,
    pane: Int,
    channel: LiveChannel?,
    nowTitle: String?,
    focused: Boolean,
    playbackActive: Boolean,
    audio: MultiviewAudioCoordinator,
    mayPlayChannel: ((LiveChannel) -> Boolean)?,
    nextStream: () -> String?,
    onNextStream: () -> Unit,
    onFocus: () -> Unit,
    onEdit: () -> Unit,
    onBackup: (failed: LiveChannel, tried: Set<String>) -> Boolean,
    modifier: Modifier,
) {
    val tokens = MaterialTheme.nuvio
    var attempt by remember(ownerId, channel) { mutableIntStateOf(0) }
    // Channels this pane already moved off because they wouldn't play (a run of backups).
    val backupTried = remember(ownerId) { HashSet<String>() }
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
                // Out of tries on this channel: a backup copy (or the game's next stream) if there is one.
                LaunchedEffect(state.error) {
                    if (state.error != null) {
                        backupTried += channel.key
                        onBackup(channel, backupTried.toSet())
                    }
                }
                // Reconnecting: nothing for the first few seconds (most drops are back by then), then a quiet mark.
                var showReconnecting by remember { mutableStateOf(false) }
                LaunchedEffect(state.isReconnecting) {
                    showReconnecting = false
                    if (state.isReconnecting) {
                        delay(RECONNECT_MARK_AFTER_MS)
                        showReconnecting = true
                    }
                }
                if (showReconnecting && state.isReconnecting) {
                    Text(
                        "Reconnecting…",
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textSecondary,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(NuvioTokens.Space.s8)
                            .clip(tokens.shapes.compactCard).background(tokens.colors.surface.copy(alpha = 0.8f))
                            .padding(horizontal = NuvioTokens.Space.s6, vertical = NuvioTokens.Space.s2),
                    )
                }
                if (state.error != null) {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(NuvioTokens.Space.s12).zIndex(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(state.error ?: "Unable to play this channel", color = tokens.colors.textPrimary, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
                            TextButton(onClick = { attempt++ }) { Text("Retry", color = tokens.colors.accent) }
                            // A game's stream: try the game's next stream in this pane.
                            nextStream()?.let { name ->
                                TextButton(onClick = onNextStream) {
                                    Text("Next: $name", color = tokens.colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
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
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
        ) {
            // The channel's logo and what's on now, like the guide.
            if (channel != null) ChannelLogo(channel, size = 22.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    channel?.name ?: "Pane ${pane + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (nowTitle != null) Text(
                    nowTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (focused) Row(
                Modifier.clip(tokens.shapes.compactCard).background(tokens.colors.accent)
                    .padding(horizontal = NuvioTokens.Space.s6, vertical = NuvioTokens.Space.s2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
            ) {
                Icon(Icons.Filled.VolumeUp, contentDescription = "Audio selected", tint = tokens.colors.onAccent, modifier = Modifier.size(14.dp))
                Text("SOUND", style = MaterialTheme.typography.labelSmall, color = tokens.colors.onAccent, fontWeight = FontWeight.Bold)
            }
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
    /** Your channel groups; the picker can show one group's channels. */
    groups: List<MultiviewPickerGroup> = emptyList(),
    group: String? = null,
    onGroup: (String?) -> Unit = {},
    /** Switches this Multiview to the Sports games for the pane (null: no Sports button). */
    onSports: ((Int) -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    var targetPane by remember { mutableStateOf(initialPane) }
    var query by remember { mutableStateOf("") }
    val target = targetPane.coerceAtMost(selection.layout.paneCount - 1)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scoped = remember(channels, groups, group) { groups.firstOrNull { it.id == group }?.channels ?: channels }
    val groupTitle = groups.firstOrNull { it.id == group }?.title
    val result by produceState(ChannelPickerSearchResult<LiveChannel>("", emptyList()), scoped) {
        channelPickerSearchResults(scoped, snapshotFlow { query }) {
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
                            MultiviewPickerControls(selection, target, singleRow = true, onTarget = { targetPane = it }, onLayout = onLayout, showTitle = true,
                                groups = groups, group = group, onGroup = onGroup, onSports = onSports?.let { open -> { open(target) } })
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
                    MultiviewPickerControls(selection, target, singleRow = singleControlRow, onTarget = { targetPane = it }, onLayout = onLayout,
                        groups = groups, group = group, onGroup = onGroup, onSports = onSports?.let { open -> { open(target) } })
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
                                if (query.isEmpty()) Text(groupTitle?.let { "Search $it for pane ${target + 1}" } ?: "Search channels for pane ${target + 1}", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                field()
                            }
                        },
                    )
                }
                NuvioBottomSheetDivider()
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    if (matches.isEmpty()) {
                        item { Text(if (channels.isEmpty()) "No available channels" else if (query.isBlank() && groupTitle != null) "No channels in $groupTitle" else "No matching channels", color = tokens.colors.textMuted, modifier = Modifier.padding(NuvioTokens.Space.s16)) }
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
    groups: List<MultiviewPickerGroup> = emptyList(),
    group: String? = null,
    onGroup: (String?) -> Unit = {},
    onSports: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    // Sports games, then your groups: "All" and each group with its channel count.
    val sources: @Composable () -> Unit = {
        if (onSports != null) {
            Row(
                Modifier.clip(tokens.shapes.compactCard).background(tokens.colors.surfaceCard)
                    .border(tokens.borders.thin, tokens.colors.borderDefault, tokens.shapes.compactCard)
                    .clickable(onClick = onSports).heightIn(min = 32.dp)
                    .padding(horizontal = NuvioTokens.Space.s10, vertical = NuvioTokens.Space.s4),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
            ) {
                Icon(Icons.Filled.SportsSoccer, contentDescription = null, tint = tokens.colors.textPrimary, modifier = Modifier.size(16.dp))
                Text("Sports", style = MaterialTheme.typography.labelMedium, color = tokens.colors.textPrimary)
            }
        }
        if (groups.isNotEmpty()) {
            Text("Groups", style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted)
            Chip("All", selected = group == null, onClick = { onGroup(null) })
            groups.forEach { g ->
                Chip("${g.title} · ${g.channels.size}", selected = group == g.id, onClick = { onGroup(g.id) })
            }
        }
    }
    val hasSources = onSports != null || groups.isNotEmpty()
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
            if (hasSources) {
                Spacer(Modifier.width(NuvioTokens.Space.s8))
                sources()
            }
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
        if (hasSources) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).heightIn(min = 40.dp).padding(horizontal = NuvioTokens.Space.s12),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) { sources() }
    }
}

private const val RECONNECT_MARK_AFTER_MS = 4_000L
