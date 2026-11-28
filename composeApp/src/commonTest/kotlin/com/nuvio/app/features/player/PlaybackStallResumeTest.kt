package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackStallResumeTest {

    @Test
    fun closedStreamAfterPlaybackKeepsTheStalledPositionForTheNextPrepare() {
        val initialPositionMs = 1_193_023L
        val stalledPositionMs = 1_245_041L
        val session = PlaybackReopenSession(initialPositionMs)

        assertEquals(initialPositionMs, session.prepareStartPositionMs())
        session.onReady()

        val reopened = session.onFailure(
            failure = PlaybackSourceFailure.ClosedStream,
            positionMs = stalledPositionMs,
            durationMs = 2_721_803L,
        )

        assertTrue(reopened)
        assertEquals(stalledPositionMs, session.prepareStartPositionMs())
        assertTrue(session.probeAttempted)

        val adoptedRewoundPosition = session.onFailure(
            failure = PlaybackSourceFailure.ReadPositionOutOfRange,
            positionMs = initialPositionMs,
            durationMs = 2_721_803L,
        )

        assertFalse(adoptedRewoundPosition)
        assertEquals(stalledPositionMs, session.prepareStartPositionMs())
        assertFalse(
            useVodCacheForSource(
                vodCacheEnabled = true,
                youtubeChunked = false,
                adaptive = false,
                loopback = true,
            ),
        )
    }

    @Test
    fun startupFailureDoesNotReplaceTheInitialPosition() {
        val session = PlaybackReopenSession(initialPositionMs = 1_193_023L)

        val reopened = session.onFailure(
            failure = PlaybackSourceFailure.ClosedStream,
            positionMs = 0L,
            durationMs = 0L,
        )

        assertFalse(reopened)
        assertEquals(1_193_023L, session.prepareStartPositionMs())
        assertFalse(session.probeAttempted)
    }

    @Test
    fun formatRetryKeepsTheCurrentPositionNearTheEndOfAnEpisode() {
        val positionMs = 2_680_000L
        for (initialPositionMs in listOf(null, 1_200_000L)) {
            val session = PlaybackReopenSession(initialPositionMs)
            session.onReady()

            assertFalse(session.onFailure(PlaybackSourceFailure.None, positionMs, 2_700_000L))
            session.markMimeProbe()
            session.onMimeProbeSucceeded(positionMs, 2_700_000L, seekableOnDemand = true)

            assertEquals(positionMs, session.prepareStartPositionMs())
            assertTrue(session.isReopening())
            assertTrue(session.onFailure(PlaybackSourceFailure.ClosedStream, 0L, 2_700_000L))
            assertEquals(positionMs, session.prepareStartPositionMs())
            session.onReady()
            assertFalse(session.isReopening())
        }
    }

    @Test
    fun formatRetryKeepsTheInitialPositionAtStartupAndForNonSeekableOrEndedPlayback() {
        val initialPositionMs = 1_200_000L
        val session = PlaybackReopenSession(initialPositionMs)
        session.markMimeProbe()
        session.onMimeProbeSucceeded(0L, 2_700_000L, seekableOnDemand = true)
        assertEquals(initialPositionMs, session.prepareStartPositionMs())

        session.onReady()
        session.onMimeProbeSucceeded(2_500_000L, 2_700_000L, seekableOnDemand = false)
        session.onMimeProbeSucceeded(2_700_000L, 2_700_000L, seekableOnDemand = true)
        assertEquals(initialPositionMs, session.prepareStartPositionMs())
        assertFalse(session.isReopening())
    }

    @Test
    fun formatRetryCanKeepThePositionOfSeekablePlaybackWithAnUnknownDuration() {
        val session = PlaybackReopenSession(initialPositionMs = null)
        session.onReady()

        assertFalse(session.onFailure(PlaybackSourceFailure.ClosedStream, 1_200_000L, 0L))
        session.markMimeProbe()
        session.onMimeProbeSucceeded(1_200_000L, 0L, seekableOnDemand = true)

        assertEquals(1_200_000L, session.prepareStartPositionMs())
    }
}
