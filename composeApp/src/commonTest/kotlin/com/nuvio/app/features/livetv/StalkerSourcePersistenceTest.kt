package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.data.LiveTvBackup
import com.nuvio.app.features.livetv.data.LiveTvCodec
import com.nuvio.app.features.livetv.data.LiveTvKeys
import com.nuvio.app.features.livetv.data.LiveTvPreferencesStore
import com.nuvio.app.features.livetv.data.PrefValue
import com.nuvio.app.features.livetv.model.PlaylistSource
import com.nuvio.app.features.livetv.stalker.StalkerCatalogCache
import com.nuvio.stalker.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Synthetic account data only; never contacts a portal. */
class StalkerSourcePersistenceTest {
    private val config = StalkerConfig("https://portal.example.invalid/stalker_portal/c/", "02:00:00:00:00:01",
        username = "fixture-user", password = "fixture-pass", serialNumber = "fixture-serial",
        deviceId = "fixture-device", deviceId2 = "fixture-device-2", signature = "fixture-signature",
        timezone = "Europe/London", language = "en")
    private val source = PlaylistSource("portal-source", "Portal", "", providerKind = "stalker", stalker = config,
        importLive = true, importVod = true)

    @Test fun sourceTypesPreserveLegacyDefaultsAndFailClosed() {
        assertTrue(PlaylistSource("m", "M3U", "https://example.invalid/list").isM3u)
        assertTrue(PlaylistSource("x", "Xtream", "", xtreamServer = "https://example.invalid").isXtream)
        assertFalse(source.isM3u)
        assertFalse(source.isXtream)
        assertEquals("", source.resolvedUrl())
        assertTrue(source.copy(importLive = false).let { !it.liveEnabled && it.importVod })
        assertTrue(source.copy(stalker = null).isStalker)
        assertEquals("", source.copy(stalker = null).resolvedUrl())
        assertFalse(source.copy(providerKind = "future", stalker = null).isM3u)
        val legacy = Json.parseToJsonElement(LiveTvCodec.encodePlaylists(listOf(PlaylistSource("legacy", "M3U", "https://example.invalid/list")))).jsonArray.single().jsonObject
        assertFalse(legacy.containsKey("sourceType"))
        assertFalse(legacy.containsKey("stalkerConfig"))
    }

    @Test fun malformedRowCannotDiscardOtherSourcesOrBecomeM3u() {
        val good = Json.parseToJsonElement(LiveTvCodec.encodePlaylists(listOf(source))) as JsonArray
        val malformed = buildJsonObject { put("id", "bad"); put("name", "Malformed"); put("stalkerConfig", "invalid") }
        val restored = LiveTvCodec.decodePlaylists(JsonArray(listOf(JsonPrimitive(7), malformed) + good).toString())
        assertEquals(2, restored.size)
        assertTrue(restored[0].isStalker)
        assertNull(restored[0].stalker)
        assertFalse(restored[0].isM3u)
        assertEquals(source, restored[1])
    }

    @Test fun tvWireNamesAndEveryConfiguredIdentityFieldRoundTrip() {
        val encoded = LiveTvCodec.encodePlaylists(listOf(source))
        val row = Json.parseToJsonElement(encoded).jsonArray.single().jsonObject
        assertEquals("stalker", row["sourceType"]?.jsonPrimitive?.content)
        assertEquals(config.toJson(), row["stalkerConfig"])
        assertFalse(row.containsKey("token"))
        assertFalse(row.containsKey("resolvedUrl"))
        assertEquals(source, LiveTvCodec.decodePlaylists(encoded).single())
    }

    @Test fun backupConfigChangesForceRefreshAndPreserveDeviceStatus() {
        val local = source.copy(lastUpdatedMs = 123L, channelCount = 16_000, serverTimezone = "UTC")
        val values = mapOf(LiveTvKeys.playlists to PrefValue.Str(LiveTvCodec.encodePlaylists(listOf(local))))
        val variants = listOf(config.copy(portalUrl = "https://other.example.invalid/c/"), config.copy(mac = "02:00:00:00:00:02"),
            config.copy(username = "changed"), config.copy(password = "changed"), config.copy(serialNumber = "changed"),
            config.copy(deviceId = "changed"), config.copy(deviceId2 = "changed"), config.copy(signature = "changed"),
            config.copy(timezone = "UTC"), config.copy(language = "fr"))
        for (changed in variants) {
            val data = buildJsonObject { put(LiveTvKeys.playlists, buildJsonObject {
                put("t", "str"); put("v", LiveTvCodec.encodePlaylists(listOf(source.copy(stalker = changed))))
            }) }
            val imported = LiveTvBackup.importInto(values, data)
            assertEquals(setOf(source.id), imported.changed)
            val row = LiveTvCodec.decodePlaylists((imported.data[LiveTvKeys.playlists] as PrefValue.Str).value).single()
            assertEquals(changed, row.stalker)
            assertEquals(123L, row.lastUpdatedMs)
            assertEquals(16_000, row.channelCount)
        }
    }

    @Test fun staleDisabledAndRemovedSourceCannotPublish() {
        val store = LiveTvPreferencesStore().apply { installPersistence(load = { null }, save = {}) }
        store.updatePlaylists { listOf(source) }
        var publications = 0
        store.withCurrentPlaylist(source) { publications++ }
        store.updatePlaylists { listOf(source.copy(stalker = config.copy(deviceId = "new"))) }
        assertFailsWith<StalkerException> { store.withCurrentPlaylist(source) { publications++ } }
        store.updatePlaylists { listOf(source.copy(enabled = false)) }
        assertFailsWith<StalkerException> { store.withCurrentPlaylist(source) { publications++ } }
        store.updatePlaylists { emptyList() }
        assertFailsWith<StalkerException> { store.withCurrentPlaylist(source) { publications++ } }
        assertEquals(1, publications)
    }

    @Test fun opaqueCommandsAndProviderIdsSurviveChannelCacheWithoutResolvedUrls() {
        val command = "ffmpeg synthetic-command-with-spaces-and-任意"
        val ref = StalkerStreamRef(StalkerContentKind.LIVE, "remote/ID|42", command, sourceIdentity = "a".repeat(64))
        val channels = listOf(StalkerChannel(ref.providerId, "Channel One", ref, groupId = "g", archiveEnabled = true, archiveDays = 7),
            StalkerChannel("other", "Channel One", ref.copy(providerId = "other"), groupId = "g"))
        val groups = listOf(StalkerGroup("g", "Sports"))
        val encoded = StalkerCatalogCache.encodeChannels(groups, channels)
        assertEquals(groups to channels, StalkerCatalogCache.decodeChannels(encoded.lineSequence()))
        assertFalse(encoded.contains("token"))
        assertEquals(2, StalkerCatalogCache.decodeChannels(encoded.lineSequence()).second.map { it.id }.toSet().size)
    }

    @Test fun sixteenThousandProviderIdsStayDistinctAndRenamesDoNotMoveFavorites() {
        val ids = (1..16_000).map { "id|/$it" }
        val keys = ids.map { StalkerCatalogCache.channelKey(source.id, it) }
        assertEquals(16_000, keys.toSet().size)
        val ref = StalkerStreamRef(StalkerContentKind.LIVE, ids[0], "opaque")
        val before = StalkerChannel(ids[0], "Original name", ref)
        val after = before.copy(name = "New name", number = "999")
        assertEquals(StalkerCatalogCache.channelKey(source.id, before.id), StalkerCatalogCache.channelKey(source.id, after.id))
        assertNotEquals(StalkerCatalogCache.channelKey(source.id, "a/b"), StalkerCatalogCache.channelKey(source.id, "a%2Fb"))
    }

    @Test fun nativeGuideKeepsArchiveIdentityUnicodeAndWholeConfiguredWindow() {
        val rows = listOf(StalkerProgramme("remote", "archive-1", "Équipe ⚽", 1_000L, 2_000L, "A\nB", "Sport", true, archiveProgramId = "archive-1"),
            StalkerProgramme("remote", "future-2", "未来", 10_000L, 11_000L, archiveAvailable = false, archiveProgramId = "future-2"))
        val encoded = StalkerCatalogCache.encodeGuide(rows, sourceIdentity = "a".repeat(64))
        val all = StalkerCatalogCache.decodeGuide(encoded.lineSequence(), 0, 20_000).getValue("remote")
        assertEquals(2, all.size)
        assertEquals("A\nB", all[0].description)
        assertEquals("archive-1", all[0].stalkerProgramId)
        assertTrue(all[0].stalkerArchiveAvailable)
        assertEquals("a".repeat(64), all[0].stalkerSourceIdentity)
        assertFalse(all[1].stalkerArchiveAvailable)
        assertEquals("future-2", all[1].stalkerProgramId)
        assertEquals("a".repeat(64), all[1].stalkerSourceIdentity)
        assertEquals(listOf(all[1]), StalkerCatalogCache.decodeGuide(encoded.lineSequence(), 9_000, 12_000).getValue("remote"))
    }
}
