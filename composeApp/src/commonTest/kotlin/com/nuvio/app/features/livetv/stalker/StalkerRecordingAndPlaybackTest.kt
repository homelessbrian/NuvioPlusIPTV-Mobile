package com.nuvio.app.features.livetv.stalker

import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.model.CatchupInfo
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.PlaylistSource
import com.nuvio.app.features.livetv.player.LiveTvCatchup
import com.nuvio.app.features.livetv.record.ChannelSnapshot
import com.nuvio.app.features.livetv.record.RecordingSchedule
import com.nuvio.stalker.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class StalkerRecordingAndPlaybackTest {
    private val source = PlaylistSource("portal-source", "Test portal", "", providerKind = "stalker",
        stalker = StalkerConfig("https://portal.example/c/", "00:11:22:33:44:55"))
    private val ref = StalkerStreamRef(StalkerContentKind.LIVE, "opaque-channel", "ffmpeg http://localhost/ch/opaque_",
        useHttpTmpLink = true, sourceIdentity = "synthetic-source-identity")
    private fun channel() = LiveChannel(key = "portal-channel", sourceId = source.id, sourceName = "Test portal",
        name = "Test channel", tvgId = null, tvgName = null, logo = null, groupId = "g", group = "Sports", number = 1,
        url = "https://cdn.example/temporary?token=must-not-persist", headers = mapOf("Authorization" to "must-not-persist"),
        catchup = CatchupInfo("stalker", null, 7, 0.0), stalker = ref)

    @Test fun scheduledRecordingRoundTripKeepsOpaqueReferenceInsteadOfTheTemporaryLink() {
        val snapshot = ChannelSnapshot.of(channel())
        val schedule = RecordingSchedule("scheduled", snapshot, "Game", null, null, 1000, 2000, true,
            stalkerProgramId = "opaque-programme", stalkerSourceIdentity = ref.sourceIdentity)
        val encoded = schedule.toJson()
        assertFalse(encoded.toString().contains("must-not-persist"))
        val restored = RecordingSchedule.fromJson(encoded)
        assertEquals(ref, restored.channel.toChannel().stalker)
        assertEquals("opaque-programme", restored.stalkerProgramId)
        assertEquals(ref.sourceIdentity, restored.stalkerSourceIdentity)
        assertEquals(source.id, restored.channel.sourceId)
        assertTrue(StalkerGateway.isLocator(restored.channel.url))
        assertTrue(restored.channel.headers.isEmpty())
        assertFalse(restored.channel.url.contains("localhost"))
    }

    @Test fun locatorNeverContainsCommandOrCredentials() {
        val locator = StalkerGateway.locator(source.id, ref)
        assertTrue(locator.startsWith("stalker:"))
        assertFalse(locator.contains("ffmpeg"))
        assertFalse(locator.contains("localhost"))
        assertFalse(locator.contains("00:11"))
    }

    @Test fun catchupRequiresTheProvidersProgrammeIdAndArchiveAvailability() {
        val now = 10_000_000L
        val programme = EpgProgram(startMs = now - 3600_000, stopMs = now - 1800_000, title = "Game")
        assertFalse(LiveTvCatchup.isReplayable(channel(), programme, now))
        assertFalse(LiveTvCatchup.isReplayable(channel(), programme.copy(stalkerProgramId = "program"), now))
        assertTrue(LiveTvCatchup.isReplayable(channel(), programme.copy(stalkerProgramId = "program", stalkerArchiveAvailable = true, stalkerSourceIdentity = ref.sourceIdentity), now))
        assertFalse(LiveTvCatchup.isReplayable(channel(), programme.copy(stalkerProgramId = "program", stalkerArchiveAvailable = true, stalkerSourceIdentity = "another-source"), now))
        assertFalse(LiveTvCatchup.isReplayable(channel(), programme.copy(stalkerProgramId = "program", stalkerArchiveAvailable = true, stalkerSourceIdentity = ref.sourceIdentity), now + 8 * 86_400_000L))
    }

    @Test fun staleReferenceCannotBeSentToAnEditedPortal() = runTest {
        LiveTvPreferences.installPersistence(load = { emptyMap() }, save = {})
        LiveTvPreferences.updatePlaylists { listOf(source.copy(stalker = source.stalker!!.copy(portalUrl = "https://replacement.example/c/"))) }
        val failure = assertFailsWith<StalkerException> { StalkerGateway.resolve(source.id, ref) }
        assertEquals(StalkerError.STALE_SESSION, failure.error)
        assertFalse(failure.toString().contains("replacement.example"))
    }

    @Test fun disabledLibraryCannotResolveAnOldMovieReference() = runTest {
        LiveTvPreferences.installPersistence(load = { emptyMap() }, save = {})
        LiveTvPreferences.updatePlaylists { listOf(source.copy(importVod = false)) }
        val failure = assertFailsWith<StalkerException> { StalkerGateway.resolve(source.id, ref.copy(kind = StalkerContentKind.VOD)) }
        assertEquals(StalkerError.STALE_SESSION, failure.error)
    }

    @Test fun portalMediaCannotEscapeThroughPlainHashingOrPartyStreamSharing() {
        val ordinary = com.nuvio.app.features.streams.StreamItem(url = "https://provider.example/movie.mp4", addonName = "Addon", addonId = "addon:test")
        assertTrue(mayUseGenericMediaRequests(false, ordinary, ordinary.url!!))
        assertFalse(mayUseGenericMediaRequests(true, ordinary, ordinary.url!!))
        assertFalse(mayUseGenericMediaRequests(false, ordinary.copy(stalkerStream = ref), ordinary.url!!))
        assertFalse(mayUseGenericMediaRequests(false, null, StalkerGateway.locator(source.id, ref)))
    }

    @Test fun portalPartyMetadataKeepsTheTitleWithoutProviderArtworkOrUrlBasenames() {
        val media = com.nuvio.app.features.party.PartyMedia(type = "series", metaId = "tmdb:1", metaType = "series",
            title = "Series", season = 2, episode = 3, poster = "https://provider.example/art?token=private",
            background = "https://provider.example/background?token=private", logo = "https://provider.example/logo?token=private",
            source = com.nuvio.app.features.party.PartySource(filename = "private-signed-token.m3u8", addonId = "livetv-ondemand"))
        val safe = partyMetadataForTransport(media, false)
        assertEquals("Series", safe.title); assertEquals(2, safe.season); assertEquals(3, safe.episode)
        assertNull(safe.poster); assertNull(safe.background); assertNull(safe.logo); assertNull(safe.source?.filename)
        assertSame(media, partyMetadataForTransport(media, true))
    }
}
