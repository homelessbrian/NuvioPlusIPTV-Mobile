package com.nuvio.app.features.livetv.data

import com.nuvio.app.features.livetv.model.ChannelSort
import com.nuvio.app.features.livetv.model.CustomGroup
import com.nuvio.app.features.livetv.model.EpgAssignment
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvSettings
import com.nuvio.app.features.livetv.model.LiveUserState
import com.nuvio.app.features.livetv.model.Reminder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelDisplayChangesTest {
    @Test
    fun unrelatedUserSavesDoNotRepeatTheDisplayTransform() = runTest {
        val initial = LiveUserState(channelNames = mapOf("channel" to "My name"))
        val h = Harness(this, initialUser = initial)
        runCurrent()
        val states = listOf(
            initial.copy(lastChannelKey = "channel"),
            initial.copy(previousChannelKey = "previous"),
            initial.copy(lastGroupId = "group"),
            initial.copy(recent = listOf("channel")),
            initial.copy(favorites = listOf("channel")),
            initial.copy(hiddenChannels = setOf("channel")),
            initial.copy(hiddenGroups = setOf("group")),
            initial.copy(lockedGroups = setOf("group")),
            initial.copy(groupOrder = listOf("group")),
            initial.copy(customGroups = listOf(CustomGroup("custom", "Custom", listOf("channel")))),
            initial.copy(epgOverrides = mapOf("channel" to EpgAssignment("source", "guide"))),
            initial.copy(channelOrder = mapOf("group" to listOf("channel"))),
            initial.copy(channelCopies = mapOf("group" to listOf("channel"))),
            initial.copy(reminders = listOf(Reminder("channel", "Channel", "Show", 123L))),
            initial.copy(channelAudio = mapOf("channel" to "en")),
            initial.copy(channelQuality = mapOf("channel" to "HD")),
            initial.copy(vodHiddenCategories = setOf("vod")),
            initial.copy(vodGroupOrder = listOf("vod")),
            initial.copy(vodGroupNames = mapOf("vod" to "Movies")),
            initial.copy(vodSorts = mapOf("vod" to "name")),
        )
        states.forEach { h.user.value = it; runCurrent() }
        assertEquals(1, h.observed.size)
        assertOverrides(initial, h.observed.single().overrides)
        // Other consumers still see the full, newest state.
        assertSame(states.last(), h.user.value)
    }

    @Test
    fun everyDisplayOverrideAndItsRemovalTriggersTheTransform() = runTest {
        val initial = LiveUserState()
        val h = Harness(this, initialUser = initial)
        runCurrent()
        val changes = listOf(
            initial.copy(channelNames = mapOf("channel" to "Renamed")),
            initial,
            initial.copy(channelNumbers = mapOf("channel" to 500)),
            initial,
            initial.copy(groupNames = mapOf("group" to "Renamed group")),
            initial,
            initial.copy(channelLogos = mapOf("channel" to "picked-logo")),
            initial,
        )
        changes.forEachIndexed { index, state ->
            h.user.value = state
            runCurrent()
            assertEquals(index + 2, h.observed.size)
            assertOverrides(state, h.observed.last().overrides)
        }
    }

    @Test
    fun blankOverrideValuesAreStillRelevantChanges() = runTest {
        val initial = LiveUserState(
            channelNames = mapOf("channel" to "Name"),
            groupNames = mapOf("group" to "Group"),
            channelLogos = mapOf("channel" to "picked-logo"),
        )
        val h = Harness(this, initialUser = initial)
        runCurrent()
        val blank = initial.copy(
            channelNames = mapOf("channel" to ""),
            groupNames = mapOf("group" to ""),
            channelLogos = mapOf("channel" to ""),
        )
        h.user.value = blank
        runCurrent()
        assertEquals(2, h.observed.size)
        assertOverrides(blank, h.observed.last().overrides)
    }

    @Test
    fun equalMapsDecodedIntoNewObjectsDoNotRepeatTheTransform() = runTest {
        val initial = LiveUserState(
            channelNames = linkedMapOf("channel" to "Name", "other" to "Other"),
            channelNumbers = linkedMapOf("channel" to 1, "other" to 2),
            groupNames = linkedMapOf("group" to "Group", "other-group" to "Other group"),
            channelLogos = linkedMapOf("channel" to "picked-logo", "other" to "other-logo"),
        )
        val h = Harness(this, initialUser = initial)
        runCurrent()
        val decoded = initial.copy(
            lastChannelKey = "channel",
            channelNames = LinkedHashMap(initial.channelNames),
            channelNumbers = LinkedHashMap(initial.channelNumbers),
            groupNames = LinkedHashMap(initial.groupNames),
            channelLogos = LinkedHashMap(initial.channelLogos),
        )
        assertNotSame(initial.channelNames, decoded.channelNames)
        assertNotSame(initial.channelNumbers, decoded.channelNumbers)
        assertNotSame(initial.groupNames, decoded.groupNames)
        assertNotSame(initial.channelLogos, decoded.channelLogos)
        h.user.value = decoded
        runCurrent()
        h.user.value = decoded.copy(
            lastGroupId = "other-group",
            // Group names are looked up directly, so their entry order is irrelevant.
            groupNames = linkedMapOf("other-group" to "Other group", "group" to "Group"),
        )
        runCurrent()
        assertEquals(1, h.observed.size)
    }

    @Test
    fun reorderingCollidingStaleOverridesRefreshesFirstMatchFallbackInputs() = runTest {
        val first = "playlist|same-guide|Old first name"
        val second = "playlist|same-guide|Old second name"
        val initial = LiveUserState(
            channelNames = linkedMapOf(first to "First name", second to "Second name"),
            channelNumbers = linkedMapOf(first to 100, second to 200),
            channelLogos = linkedMapOf(first to "first-logo", second to "second-logo"),
        )
        val h = Harness(this, initialUser = initial)
        runCurrent()
        // The current key is absent from all maps; stable-ID fallback uses the first stale entry.
        val replacement = listOf(h.channels.value.single().copy(key = "playlist|same-guide|New provider name"))
        h.channels.value = replacement
        runCurrent()
        var expectedCalls = 2
        val reorderedNames = initial.copy(
            recent = listOf(replacement.single().key),
            channelNames = linkedMapOf(second to "Second name", first to "First name"),
        )
        val reorderedNumbers = reorderedNames.copy(
            channelAudio = mapOf(replacement.single().key to "en"),
            channelNumbers = linkedMapOf(second to 200, first to 100),
        )
        val reorderedLogos = reorderedNumbers.copy(
            channelQuality = mapOf(replacement.single().key to "HD"),
            channelLogos = linkedMapOf(second to "second-logo", first to "first-logo"),
        )
        // Map equality alone misses these changes; unrelated fields make each full state emit.
        assertEquals(initial.channelNames, reorderedNames.channelNames)
        assertEquals(initial.channelNumbers, reorderedNumbers.channelNumbers)
        assertEquals(initial.channelLogos, reorderedLogos.channelLogos)
        listOf(reorderedNames, reorderedNumbers, reorderedLogos).forEach { state ->
            h.user.value = state
            runCurrent()
            assertEquals(++expectedCalls, h.observed.size)
            val input = h.observed.last()
            assertSame(replacement, input.channels)
            assertEquals(state.channelNames.entries.toList(), input.overrides.channelNames.entries.toList())
            assertEquals(state.channelNumbers.entries.toList(), input.overrides.channelNumbers.entries.toList())
            assertEquals(state.channelLogos.entries.toList(), input.overrides.channelLogos.entries.toList())
            assertSame(state.channelNames, input.overrides.channelNames)
            assertSame(state.channelNumbers, input.overrides.channelNumbers)
            assertSame(state.channelLogos, input.overrides.channelLogos)
        }
        h.user.value = reorderedLogos.copy(lastGroupId = "other")
        runCurrent()
        assertEquals(expectedCalls, h.observed.size)
    }

    @Test
    fun profileResetClearsEveryOverrideAfterUnrelatedSaves() = runTest {
        val initial = LiveUserState(
            channelNames = mapOf("channel" to "Name"),
            channelNumbers = mapOf("channel" to 500),
            groupNames = mapOf("group" to "Group"),
            channelLogos = mapOf("channel" to "picked-logo"),
        )
        val h = Harness(this, initialUser = initial)
        runCurrent()
        h.user.value = initial.copy(channelAudio = mapOf("channel" to "en"))
        runCurrent()
        val reset = LiveUserState()
        h.user.value = reset
        runCurrent()
        h.user.value = reset.copy(lastGroupId = "other")
        runCurrent()
        assertEquals(2, h.observed.size)
        assertOverrides(initial, h.observed.first().overrides)
        assertOverrides(reset, h.observed.last().overrides)
    }

    @Test
    fun relevantChangesAfterSkippedSavesUseTheNewestOverrides() = runTest {
        val h = Harness(this)
        runCurrent()
        h.user.value = LiveUserState(recent = listOf("channel"))
        runCurrent()
        val renamed = h.user.value.copy(channelNames = mapOf("channel" to "First"))
        h.user.value = renamed
        runCurrent()
        h.user.value = renamed.copy(channelQuality = mapOf("channel" to "HD"))
        runCurrent()
        val renamedAgain = h.user.value.copy(channelNames = mapOf("channel" to "Second"))
        h.user.value = renamedAgain
        runCurrent()
        assertEquals(3, h.observed.size)
        assertOverrides(renamed, h.observed[1].overrides)
        assertOverrides(renamedAgain, h.observed[2].overrides)
    }

    @Test
    fun unrelatedSettingsDoNotRepeatTheDisplayTransform() = runTest {
        val initial = LiveTvSettings(nameRemovals = "HD")
        val h = Harness(this, initialSettings = initial)
        runCurrent()
        val changes = listOf(
            initial.copy(infoBannerSeconds = 10),
            initial.copy(audioPassthrough = !initial.audioPassthrough),
            initial.copy(epgOffsetMinutes = 60, epgPastHours = 12, epgFutureDays = 7),
            initial.copy(playlistRefreshHours = 12, epgRefreshHours = 6),
            initial.copy(showChannelNames = false, showChannelNumbers = false, showChannelLogos = false),
            initial.copy(phoneChannelNames = false, phoneChannelNumbers = false, phoneChannelLogos = false),
            initial.copy(showFavoritesGroup = false, showRecentGroup = false, showAllChannelsGroup = false),
            initial.copy(channelSort = ChannelSort.NAME, groupSort = "name", sequentialNumbers = true),
            initial.copy(parentalPin = "0000", lockAdultContent = false),
            initial.copy(homeRowEnabled = false, homeRowSource = "recent", homeRowLimit = 5),
            initial.copy(recordPreMinutes = 5, recordPostMinutes = 10),
            initial.copy(bufferSize = "large", timeshiftEnabled = false),
        )
        changes.forEach { h.settings.value = it; runCurrent() }
        assertEquals(1, h.observed.size)
        assertEquals(initial.nameRemovals, h.observed.single().nameRemovals)
        assertSame(changes.last(), h.settings.value)
    }

    @Test
    fun changingAndClearingNameRemovalTermsTriggersTheTransform() = runTest {
        val initial = LiveTvSettings(nameRemovals = "HD")
        val h = Harness(this, initialSettings = initial)
        runCurrent()
        h.settings.value = initial.copy(infoBannerSeconds = 10)
        runCurrent()
        val changed = h.settings.value.copy(nameRemovals = "HD, FHD")
        h.settings.value = changed
        runCurrent()
        h.settings.value = changed.copy(epgOffsetMinutes = 60)
        runCurrent()
        val cleared = h.settings.value.copy(nameRemovals = "")
        h.settings.value = cleared
        runCurrent()
        assertEquals(listOf("HD", "HD, FHD", ""), h.observed.map { it.nameRemovals })
    }

    @Test
    fun rawChannelReplacementStillTransformsWithCurrentDisplayOverrides() = runTest {
        val initial = LiveUserState(channelNames = mapOf("channel" to "My name"))
        val h = Harness(this, initialUser = initial)
        runCurrent()
        h.user.value = initial.copy(lastChannelKey = "channel")
        h.settings.value = h.settings.value.copy(infoBannerSeconds = 10)
        runCurrent()
        val replacement = listOf(h.channels.value.single().copy(name = "Provider's new name"))
        h.channels.value = replacement
        runCurrent()
        assertEquals(2, h.observed.size)
        assertSame(replacement, h.observed.last().channels)
        assertOverrides(initial, h.observed.last().overrides)
        h.channels.value = emptyList()
        runCurrent()
        assertEquals(3, h.observed.size)
        assertEquals(emptyList(), h.observed.last().channels)
    }

    @Test
    fun guideLogoAndPreferredSourceUpdatesStillTriggerTheTransform() = runTest {
        val h = Harness(this)
        runCurrent()
        val logos = mapOf("channel" to "guide-logo")
        h.logos.value = logos
        runCurrent()
        assertSame(logos, h.observed.last().logos)
        val sources = setOf("playlist")
        h.preferEpg.value = sources
        runCurrent()
        assertSame(sources, h.observed.last().preferEpg)
        assertSame(logos, h.observed.last().logos)
        h.logos.value = emptyMap()
        runCurrent()
        assertEquals(emptyMap(), h.observed.last().logos)
        h.preferEpg.value = emptySet()
        runCurrent()
        assertEquals(emptySet(), h.observed.last().preferEpg)
        assertEquals(5, h.observed.size)
    }

    @Test
    fun displayInputsReuseOnlyTheRelevantMaps() = runTest {
        val initial = LiveUserState(
            channelNames = mapOf("channel" to "Name"),
            channelNumbers = mapOf("channel" to 500),
            groupNames = mapOf("group" to "Group"),
            channelLogos = mapOf("channel" to "picked-logo"),
            reminders = listOf(Reminder("channel", "Channel", "Show", 123L)),
            channelAudio = mapOf("channel" to "en"),
        )
        val h = Harness(this, initialUser = initial)
        runCurrent()
        // The immutable input type has only these maps; no old full user snapshot is retained.
        val actual = h.observed.single().overrides
        assertSame(initial.channelNames, actual.channelNames)
        assertSame(initial.channelNumbers, actual.channelNumbers)
        assertSame(initial.groupNames, actual.groupNames)
        assertSame(initial.channelLogos, actual.channelLogos)
    }

    private fun assertOverrides(expected: LiveUserState, actual: ChannelDisplayOverrides) {
        assertEquals(expected.channelNames, actual.channelNames)
        assertEquals(expected.channelNumbers, actual.channelNumbers)
        assertEquals(expected.groupNames, actual.groupNames)
        assertEquals(expected.channelLogos, actual.channelLogos)
    }

    private data class Inputs(
        val channels: List<LiveChannel>,
        val overrides: ChannelDisplayOverrides,
        val cleanup: ChannelNameCleanup,
        val logos: Map<String, String>,
        val preferEpg: Set<String>,
    ) {
        val nameRemovals: String get() = cleanup.removals
    }

    private class Harness(
        scope: TestScope,
        initialUser: LiveUserState = LiveUserState(),
        initialSettings: LiveTvSettings = LiveTvSettings(),
    ) {
        val channels = MutableStateFlow(listOf(LiveChannel(
            key = "channel", sourceId = "playlist", sourceName = "Playlist", name = "Channel",
            tvgId = "guide", tvgName = null, logo = null, groupId = "group", group = "Group",
            number = 1, url = "",
        )))
        val user = MutableStateFlow(initialUser)
        val settings = MutableStateFlow(initialSettings)
        val logos = MutableStateFlow<Map<String, String>>(emptyMap())
        val preferEpg = MutableStateFlow<Set<String>>(emptySet())
        val observed = mutableListOf<Inputs>()

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                displayChannelChanges(channels, user, settings, logos, preferEpg) { c, u, s, l, p ->
                    observed += Inputs(c, u, s, l, p)
                    c
                }.collect()
            }
        }
    }
}
