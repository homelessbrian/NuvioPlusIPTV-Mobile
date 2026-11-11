package com.nuvio.app.features.livetv.multiview

import com.nuvio.app.features.livetv.model.LiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultiviewSessionTest {
    @Test
    fun layoutsHaveTheRequestedRowsWithoutDuplicatePanes() {
        assertEquals(listOf(listOf(0)), MultiviewLayout.SINGLE.rows)
        assertEquals(listOf(listOf(0, 1)), MultiviewLayout.DUAL.rows)
        assertEquals(listOf(listOf(0, 1), listOf(2)), MultiviewLayout.TRIPLE.rows)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), MultiviewLayout.QUAD.rows)
        MultiviewLayout.entries.forEach { assertEquals((0 until it.paneCount).toList(), it.rows.flatten()) }
    }

    @Test
    fun selectingAndClearingOtherPanesNeverStealsAudio() {
        val initial = MultiviewSelection().assign(0, channel("one")).assign(1, channel("two"))
        assertNull(initial.focusedPane)
        val focused = initial.focus(0)
        assertEquals(0, focused.assign(1, channel("replacement")).focusedPane)
        assertEquals(0, focused.assign(1, null).focusedPane)
        assertNull(focused.assign(0, null).focusedPane)
        assertEquals(focused, focused.focus(3))
    }

    @Test
    fun reducingLayoutMutesHiddenFocusAndRetainsAssignmentsForLater() {
        val quad = MultiviewSelection(layout = MultiviewLayout.QUAD).assign(3, channel("four")).focus(3)
        val dual = quad.withLayout(MultiviewLayout.DUAL)
        assertNull(dual.focusedPane)
        assertEquals("four", dual.channels[3]?.key)
        assertNull(dual.withLayout(MultiviewLayout.QUAD).focusedPane)
        assertEquals(dual, dual.assign(3, channel("disallowed-hidden-change")))
    }

    @Test
    fun parentalOrVisibilityChangesClearAllDisallowedPanesIncludingHiddenOnes() {
        val selected = MultiviewSelection(layout = MultiviewLayout.QUAD)
            .assign(0, channel("visible"))
            .assign(1, channel("locked"))
            .assign(3, channel("hidden"))
            .focus(1)
            .withLayout(MultiviewLayout.DUAL)
        val refreshed = channel("visible").copy(name = "Updated channel")
        val permitted = selected.retainAllowed(mapOf(refreshed.key to refreshed))
        assertEquals(refreshed, permitted.channels[0])
        assertNull(permitted.channels[1])
        assertNull(permitted.channels[3])
        assertNull(permitted.focusedPane)
    }

    @Test
    fun profileSessionsDoNotShareAssignmentsOrAudio() {
        val first = MultiviewSession(profileId = 1)
        first.assign(0, channel("one"))
        first.focus(0)
        val nextProfile = MultiviewSession(profileId = 2)
        assertTrue(first.isForProfile(1))
        assertFalse(first.isForProfile(2))
        assertFalse(first.isForProfile(null))
        assertTrue(nextProfile.isForProfile(2))
        assertTrue(nextProfile.selection.value.channels.all { it == null })
        assertNull(nextProfile.selection.value.focusedPane)
        assertEquals("one", first.selection.value.channels[0]?.key)
    }

    @Test
    fun removingSelectedMiddleSlotCompactsStableOwnersAndPreservesRemainingAudio() {
        val quad = (0..3).fold(MultiviewSelection(layout = MultiviewLayout.QUAD)) { selected, index ->
            selected.assign(index, channel("$index"))
        }.focus(3)
        val removed = quad.remove(1)
        assertEquals(MultiviewLayout.TRIPLE, removed.layout)
        assertEquals(listOf("0", "2", "3", null), removed.channels.map { it?.key })
        assertEquals(listOf(quad.paneIds[0], quad.paneIds[2], quad.paneIds[3]), removed.paneIds.take(3))
        assertTrue(removed.paneIds[3] !in quad.paneIds)
        assertEquals(2, removed.focusedPane)
        assertEquals(quad.focusedOwnerId, removed.focusedOwnerId)
        assertTrue(removed.channels[2] === quad.channels[3])
        assertTrue(removed.channels[1] === quad.channels[2])
    }

    @Test
    fun removingAudibleSlotChoosesClosestPopulatedNeighborAndFinalPaneCannotBeRemoved() {
        val dual = MultiviewSelection().assign(0, channel("one")).assign(1, channel("two")).focus(0)
        val single = dual.remove(0)
        assertEquals(MultiviewLayout.SINGLE, single.layout)
        assertEquals("two", single.channels[0]?.key)
        assertEquals(dual.paneIds[1], single.paneIds[0])
        assertEquals(0, single.focusedPane)
        assertTrue(single === single.remove(0))
        assertTrue(single === single.remove(-1))
        assertTrue(dual === dual.remove(2))
        val emptyNeighbor = MultiviewSelection(layout = MultiviewLayout.QUAD)
            .assign(0, channel("one")).assign(3, channel("four")).focus(3).remove(3)
        assertEquals(0, emptyNeighbor.focusedPane)
    }

    @Test
    fun removingThenAddingDoesNotRestoreRemovedBroadcastsOrReuseItsOwner() {
        val initial = (0..3).fold(MultiviewSelection(layout = MultiviewLayout.QUAD)) { selected, index ->
            selected.assign(index, channel("$index"))
        }.focus(2)
        val removed = initial.remove(1).remove(0).remove(1)
        assertEquals(MultiviewLayout.SINGLE, removed.layout)
        assertEquals("2", removed.channels[0]?.key)
        assertEquals(initial.paneIds[2], removed.paneIds[0])
        assertEquals(0, removed.focusedPane)
        assertTrue(removed.channels.drop(1).all { it == null })
        val expanded = removed.withLayout(MultiviewLayout.QUAD)
        assertEquals(4, expanded.paneIds.toSet().size)
        assertEquals("2", expanded.channels[0]?.key)
        assertTrue(expanded.channels.drop(1).all { it == null })
        val reduced = initial.withLayout(MultiviewLayout.DUAL).remove(0).withLayout(MultiviewLayout.QUAD)
        assertEquals("1", reduced.channels[0]?.key)
        assertTrue(reduced.channels.drop(1).all { it == null })
    }

    @Test
    fun compactionKeepsAudioOnTheStableOwnerUntilAnAudibleRemovedPaneChoosesItsNeighbor() {
        // After an earlier remove/add, every owner ID differs from its slot index. Using the
        // compacted slot as an audio key would select a different stream in several cases below.
        val initial = MultiviewSelection(layout = MultiviewLayout.QUAD).remove(0)
            .withLayout(MultiviewLayout.QUAD)
        val populated = (0..3).fold(initial) { selected, index -> selected.assign(index, channel("$index")) }
        for (removed in 0..3) for (audible in 0..3) {
            val before = populated.focus(audible)
            assertEquals(before.paneIds[audible], before.focusedOwnerId)
            val calls = mutableListOf<String>()
            val players = before.paneIds.map { FakePlayer(it, calls) }
            val coordinator = MultiviewAudioCoordinator()
            players.forEachIndexed { slot, player -> coordinator.register(before.paneIds[slot], player) }
            coordinator.focus(before.focusedOwnerId)
            calls.clear()

            val after = before.remove(removed)
            val expected = if (removed == audible) before.paneIds[if (removed < 3) removed + 1 else removed - 1]
                else before.paneIds[audible]
            assertEquals(expected, after.focusedOwnerId)
            // Composition disposes only the removed owner; survivors keep their registrations.
            coordinator.unregister(before.paneIds[removed], players[removed])
            coordinator.focus(after.focusedOwnerId)

            assertEquals(listOf(expected), players.filterNot { it.muted }.map { it.index })
            assertTrue(calls.filter { it.endsWith(":false") }.all { it == "$expected:false" },
                "Compaction briefly enabled a different broadcast: $calls")
        }
    }

    @Test
    fun everyAudioHandoffMutesAllRegisteredPlayersBeforeUnmutingTarget() {
        val calls = mutableListOf<String>()
        val players = List(4) { FakePlayer(it, calls) }
        val coordinator = MultiviewAudioCoordinator()
        players.forEachIndexed { index, player -> coordinator.register(index, player) }
        assertTrue(players.all { it.muted })
        coordinator.focus(0)
        calls.clear()
        coordinator.focus(3)
        assertEquals(listOf("0:true", "1:true", "2:true", "3:true", "3:false"), calls)
        assertEquals(listOf(3), players.filterNot { it.muted }.map { it.index })
        // A native focus loss may silence a player without changing the selected pane.
        calls.clear()
        coordinator.focus(3)
        assertEquals(listOf("0:true", "1:true", "2:true", "3:true", "3:false"), calls)
        coordinator.muteAll()
        assertTrue(players.all { it.muted })
    }

    @Test
    fun registrationReplacementAndRemovalNeverLeaveTwoPlayersAudible() {
        val calls = mutableListOf<String>()
        val players = mutableListOf<FakePlayer>()
        fun player(index: Int) = FakePlayer(index, calls) {
            assertTrue(players.count { !it.muted } <= 1, "Multiple audible players during $calls")
        }.also { players += it }
        val first = player(0)
        val second = player(1)
        val replacement = player(1)
        val coordinator = MultiviewAudioCoordinator()
        coordinator.focus(0)
        coordinator.register(0, first)
        coordinator.register(1, second)
        coordinator.focus(1)
        coordinator.register(1, replacement)
        assertTrue(second.muted)
        assertTrue(!replacement.muted)
        // A late disposal callback from the replaced owner must not unregister its replacement.
        coordinator.unregister(1, second)
        assertTrue(!replacement.muted)
        coordinator.unregister(1, replacement)
        assertTrue(players.all { it.muted })
        coordinator.muteAll()
    }

    private class FakePlayer(
        val index: Int,
        private val calls: MutableList<String>,
        private val changed: () -> Unit = {},
    ) : MultiviewPlayer {
        override val state = MutableStateFlow(MultiviewTileState())
        var muted = true
            private set
        override fun play(channel: LiveChannel) = Unit
        override fun setMuted(muted: Boolean) {
            this.muted = muted
            calls += "$index:$muted"
            changed()
        }
        override fun setPaused(paused: Boolean) = Unit
        override fun release() = Unit
    }

    private fun channel(key: String) = LiveChannel(
        key = key,
        sourceId = "fixture",
        sourceName = "Fixture",
        name = key,
        tvgId = null,
        tvgName = null,
        logo = null,
        groupId = "fixture:group",
        group = "Channels",
        number = 1,
        url = "https://example.invalid/live.m3u8",
    )
}
