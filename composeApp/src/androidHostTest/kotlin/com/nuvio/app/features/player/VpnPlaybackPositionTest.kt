package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VpnPlaybackPositionTest {

    @Test
    fun pairedRestoreRevisionsKeepTheLastPlayablePosition() {
        val position = VpnPlaybackPosition()
        assertNull(position.forReload(1))
        position.record(1, playable(682_000))
        position.record(1, playable(1_200_000))
        assertNull(position.forReload(1))
        assertEquals(1_200_000L, position.forReload(2))

        position.record(2, PlayerPlaybackSnapshot())
        position.record(2, playable(0).copy(isLoading = true))
        assertEquals(1_200_000L, position.forReload(3))

        position.record(3, playable(1_202_000))
        position.record(1, playable(682_000))
        assertEquals(1_202_000L, position.forReload(4))
    }

    @Test
    fun unseekableAndOpeningSourcesDoNotStoreAResumePosition() {
        val position = VpnPlaybackPosition()
        position.record(1, playable(1_200_000).copy(isSeekable = false))
        position.record(1, playable(1_200_000).copy(isOpening = true))
        assertNull(position.forReload(2))
    }

    @Test
    fun anIntentionalSeekToZeroIsSavedButTheEndClearsIt() {
        val position = VpnPlaybackPosition()
        position.record(1, playable(1_200_000))
        position.record(1, playable(0))
        assertEquals(0L, position.forReload(2))

        position.record(1, playable(2_700_000).copy(isEnded = true))
        assertNull(position.forReload(2))
    }

    private fun playable(positionMs: Long) = PlayerPlaybackSnapshot(
        isLoading = false,
        durationMs = 2_700_000,
        positionMs = positionMs,
        isSeekable = true,
    )
}
