package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.model.CatchupInfo
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.ui.archiveChannelsForViewport
import com.nuvio.app.features.livetv.ui.mergeGuideArchiveRows
import com.nuvio.stalker.StalkerContentKind
import com.nuvio.stalker.StalkerStreamRef
import kotlin.test.*

class StalkerArchiveViewportTest {
    private fun channel(id: String) = LiveChannel(id, "source", "Portal", id, null, null, null, "g", "Sports", 1,
        "stalker://source/live/$id", catchup = CatchupInfo("stalker", null, 7),
        stalker = StalkerStreamRef(StalkerContentKind.LIVE, id, "opaque", sourceIdentity = "a".repeat(64)))

    @Test fun onlyVisibleMissingPastRowsRequestArchivesInLargeGroup() {
        val all = (1..16_000).map { channel(it.toString()) }
        val visible = all.subList(500, 508)
        val covered = EpgProgram(1_000, 3_000, "Archived", stalkerProgramId = "p", stalkerArchiveAvailable = true, stalkerSourceIdentity = "a".repeat(64))
        val cache = mapOf(visible[0].key to listOf(covered))
        val requested = archiveChannelsForViewport(visible, 1_000, 3_000, 4_000, cache)
        assertEquals(7, requested.size)
        assertTrue(requested.all { it in visible })
        assertFalse(visible[0] in requested)
        assertEquals(emptyList(), archiveChannelsForViewport(visible, 4_000, 5_000, 4_000, emptyMap()))
        assertEquals(emptyList(), archiveChannelsForViewport(listOf(visible[0].copy(stalker = null)), 1_000, 3_000, 4_000, emptyMap()))
    }

    @Test fun gapsAndPreviouslyFutureListingsAreVerifiedWithoutDroppingCoverage() {
        val channel = channel("live")
        val past = EpgProgram(1_000, 2_000, "Past", stalkerProgramId = "p")
        val future = EpgProgram(5_000, 6_000, "Future")
        assertEquals(listOf(channel), archiveChannelsForViewport(listOf(channel), 1_000, 3_000, 4_000,
            mapOf(channel.key to listOf(past))))
        val refreshed = past.copy(stalkerArchiveAvailable = true, stalkerSourceIdentity = "a".repeat(64))
        val merged = mergeGuideArchiveRows(listOf(past, future), listOf(refreshed))
        assertEquals(listOf(refreshed, future), merged)
        assertEquals(emptyList(), archiveChannelsForViewport(listOf(channel), 1_000, 2_000, 4_000, mapOf(channel.key to merged)))
    }
}
