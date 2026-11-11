package com.nuvio.app.features.livetv.multiview

import com.nuvio.app.features.livetv.model.LiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class MultiviewLayout(val paneCount: Int, val title: String) {
    SINGLE(1, "Single"), DUAL(2, "Dual"), TRIPLE(3, "Triple"), QUAD(4, "Quad");

    /** Rows are shared by rendering and tests; Dual always remains side by side. */
    val rows: List<List<Int>>
        get() = when (this) {
            SINGLE -> listOf(listOf(0))
            DUAL -> listOf(listOf(0, 1))
            TRIPLE -> listOf(listOf(0, 1), listOf(2))
            QUAD -> listOf(listOf(0, 1), listOf(2, 3))
        }
}

data class MultiviewSelection(
    val layout: MultiviewLayout = MultiviewLayout.DUAL,
    val channels: List<LiveChannel?> = List(4) { null },
    /** Overlay controls never change this. Only a tap on a populated video chooses audio. */
    val focusedPane: Int? = null,
    /** Stable composition/native owners: compacting slots must not restart surviving streams. */
    val paneIds: List<Int> = List(4) { it },
    val nextPaneId: Int = 4,
) {
    /** Audio follows the native owner when its visible slot moves after another pane is removed. */
    val focusedOwnerId: Int?
        get() = focusedPane?.takeIf { it in 0 until layout.paneCount }?.let { paneIds.getOrNull(it) }

    fun withLayout(next: MultiviewLayout) = copy(
        layout = next,
        focusedPane = focusedPane?.takeIf { it < next.paneCount },
    )

    fun assign(pane: Int, channel: LiveChannel?): MultiviewSelection {
        if (pane !in 0 until layout.paneCount) return this
        return copy(
            channels = channels.mapIndexed { index, old -> if (index == pane) channel else old },
            focusedPane = focusedPane?.takeUnless { it == pane && channel == null },
        )
    }

    fun focus(pane: Int): MultiviewSelection =
        if (pane in 0 until layout.paneCount && channels[pane] != null) copy(focusedPane = pane) else this

    fun remove(pane: Int): MultiviewSelection {
        if (layout.paneCount <= 1 || pane !in 0 until layout.paneCount) return this
        val count = layout.paneCount - 1
        val remaining = channels.toMutableList().apply {
            removeAt(pane)
            add(null)
            for (index in count until size) this[index] = null
        }
        val owners = paneIds.toMutableList().apply { removeAt(pane); add(nextPaneId) }
        val focus = when {
            focusedPane == null -> null
            focusedPane > pane -> focusedPane - 1
            focusedPane < pane -> focusedPane
            else -> (0 until count).filter { remaining[it] != null }
                .minByOrNull { kotlin.math.abs(it - pane) }
        }
        return copy(layout = MultiviewLayout.entries.first { it.paneCount == count },
            channels = remaining, paneIds = owners, nextPaneId = nextPaneId + 1, focusedPane = focus)
    }

    fun retainAllowed(allowed: Map<String, LiveChannel>): MultiviewSelection {
        val refreshed = channels.map { old -> old?.let { allowed[it.key] } }
        return copy(channels = refreshed, focusedPane = focusedPane?.takeIf { refreshed[it] != null })
    }
}

/** In-memory only, recreated for every profile. No stream URLs enter routes or saved state. */
class MultiviewSession(val profileId: Int? = null) {
    private val mutableSelection = MutableStateFlow(MultiviewSelection())
    val selection: StateFlow<MultiviewSelection> = mutableSelection.asStateFlow()

    fun isForProfile(currentProfileId: Int?): Boolean = profileId == currentProfileId

    fun setLayout(layout: MultiviewLayout) = mutableSelection.update { it.withLayout(layout) }
    fun assign(pane: Int, channel: LiveChannel?) = mutableSelection.update { it.assign(pane, channel) }
    fun focus(pane: Int) = mutableSelection.update { it.focus(pane) }
    fun removePane(pane: Int) = mutableSelection.update { it.remove(pane) }
    fun retainAllowed(channels: Map<String, LiveChannel>) = mutableSelection.update { it.retainAllowed(channels) }
}

/** Audio is keyed by stable native owner, independently of its current visible slot. */
internal class MultiviewAudioCoordinator {
    private val players = LinkedHashMap<Int, MultiviewPlayer>()
    private var focusedOwnerId: Int? = null

    fun register(ownerId: Int, player: MultiviewPlayer) {
        player.setMuted(true)
        players.put(ownerId, player)?.takeUnless { it === player }?.setMuted(true)
        reconcile()
    }

    fun unregister(ownerId: Int, player: MultiviewPlayer) {
        player.setMuted(true)
        if (players[ownerId] === player) players.remove(ownerId)
        reconcile()
    }

    fun focus(ownerId: Int?) {
        focusedOwnerId = ownerId
        reconcile()
    }

    fun muteAll() {
        focusedOwnerId = null
        players.values.forEach { it.setMuted(true) }
    }

    private fun reconcile() {
        players.values.forEach { it.setMuted(true) }
        focusedOwnerId?.let { players[it]?.setMuted(false) }
    }
}
