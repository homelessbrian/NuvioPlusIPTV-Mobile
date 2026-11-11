package com.nuvio.vpn.android

import com.nuvio.app.features.livetv.stalker.StalkerMediaHttp
import com.nuvio.app.features.livetv.stalker.StalkerMediaPolicy
import com.nuvio.app.features.livetv.stalker.stalkerHttpGet
import com.nuvio.app.features.livetv.stalker.stalkerHttpGuide
import com.nuvio.app.features.livetv.stalker.stalkerRouteIdentity
import com.nuvio.app.features.livetv.stalker.consistentStalkerRoutes
import com.nuvio.app.features.livetv.stalker.stalkerProbeStreamType
import com.nuvio.app.features.livetv.stalker.readStalkerProbeBody
import com.nuvio.app.features.livetv.ondemand.OnDemandPlayback
import com.nuvio.app.features.livetv.stalker.StalkerGateway
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.model.PlaylistSource
import com.nuvio.app.features.profiles.ProfileStorage
import com.nuvio.stalker.StalkerConfig
import com.nuvio.stalker.StalkerStreamRef
import com.nuvio.stalker.StalkerContentKind
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import com.nuvio.app.features.streams.StreamLinkCacheStorage
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.stalker.StalkerError
import com.nuvio.stalker.StalkerException
import com.nuvio.stalker.StalkerMediaAuthorization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import okio.buffer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertSame

/** Simulated HTTP endpoints and Android settings only; no real portal or VPN connection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StalkerProtectedPlaybackTest {
    @Before fun setUp() {
        VpnPlaybackProtection.initialize(RuntimeEnvironment.getApplication())
        VpnPlaybackProtection.setActiveUserProfile(1)
        AndroidVpnSettings(RuntimeEnvironment.getApplication()).save(com.nuvio.vpn.core.VpnPreferences.defaults())
    }

    @Test fun accountHeadersStayOnThePortalAcrossRedirectsAndSegmentRequests() = runBlocking(Dispatchers.IO) {
        MockWebServer().use { portal -> MockWebServer().use { cdn ->
            portal.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/playlist.m3u8")))
            cdn.enqueue(MockResponse().setBody("#EXTM3U"))
            cdn.enqueue(MockResponse().setBody("segment"))
            portal.enqueue(MockResponse().setBody("same origin"))
            val policy = StalkerMediaPolicy(StalkerMediaAuthorization(portal.url("/portal.php").toString(),
                mapOf("Authorization" to "Bearer synthetic-token", "Cookie" to "mac=synthetic-mac", "SN" to "synthetic-serial", "X-User-Agent" to "Model: synthetic",
                    "Origin" to portal.url("/").toString(), "Referer" to portal.url("/c/").toString())), stalkerRouteIdentity()) { true }
            val calls = StalkerMediaHttp.calls(policy)
            calls.newCall(Request.Builder().url(portal.url("/master.m3u8")).build()).execute().use { assertTrue(it.isSuccessful) }
            val initial = portal.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("Bearer synthetic-token", initial.getHeader("Authorization"))
            assertEquals("mac=synthetic-mac", initial.getHeader("Cookie"))
            assertEquals("synthetic-serial", initial.getHeader("SN"))
            val redirect = cdn.takeRequest(5, TimeUnit.SECONDS)!!
            assertNull(redirect.getHeader("Authorization")); assertNull(redirect.getHeader("Cookie"))
            assertNull(redirect.getHeader("SN")); assertNull(redirect.getHeader("X-User-Agent"))
            assertNull(redirect.getHeader("Origin")); assertNull(redirect.getHeader("Referer"))
            calls.newCall(Request.Builder().url(cdn.url("/piece.ts")).header("Authorization", "must-strip")
                .header("Cookie", "must-strip").build()).execute().close()
            val segment = cdn.takeRequest(5, TimeUnit.SECONDS)!!
            assertNull(segment.getHeader("Authorization")); assertNull(segment.getHeader("Cookie"))
            assertNull(segment.getHeader("SN")); assertNull(segment.getHeader("X-User-Agent"))
            assertNull(segment.getHeader("Origin")); assertNull(segment.getHeader("Referer"))
            calls.newCall(Request.Builder().url(portal.url("/piece.ts")).build()).execute().close()
            assertEquals("Bearer synthetic-token", portal.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        } }
    }

    @Test fun retiredMediaContextFailsBeforeSendingAnyRequest() = runBlocking(Dispatchers.IO) {
        MockWebServer().use { server ->
            val policy = StalkerMediaPolicy(StalkerMediaAuthorization(server.url("/portal.php").toString(), emptyMap()), stalkerRouteIdentity()) { false }
            assertFailsWith<java.io.IOException> {
                StalkerMediaHttp.calls(policy).newCall(Request.Builder().url(server.url("/video")).build()).execute()
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun apiRedirectCannotCarryTheMacOrBearerToAnotherOrigin() = runBlocking(Dispatchers.IO) {
        MockWebServer().use { portal -> MockWebServer().use { other ->
            portal.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/steal")))
            val failure = assertFailsWith<StalkerException> {
                stalkerHttpGet(portal.url("/portal.php").toString(),
                    mapOf("Authorization" to "Bearer synthetic-token", "Cookie" to "mac=synthetic"), stalkerRouteIdentity())
            }
            assertEquals(StalkerError.INVALID_RESPONSE, failure.error)
            assertEquals(1, portal.requestCount); assertEquals(0, other.requestCount)
        } }
    }

    @Test fun apiSameOriginRedirectKeepsItsScopedCredentials() = runBlocking(Dispatchers.IO) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/nested/portal.php"))
            server.enqueue(MockResponse().setBody("{\"js\":[]}"))
            val reply = stalkerHttpGet(server.url("/portal.php").toString(), mapOf("Cookie" to "mac=synthetic"), stalkerRouteIdentity())
            assertEquals(200, reply.status)
            server.takeRequest(5, TimeUnit.SECONDS)
            assertEquals("mac=synthetic", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Cookie"))
        }
    }

    @Test fun cancellationClosesAStalledGuideRequest() = runBlocking(Dispatchers.IO) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val route = stalkerRouteIdentity()
            val job = async { stalkerHttpGuide(server.url("/portal.php").toString(), emptyMap(), route) { _, _ -> } }
            withTimeout(5_000) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { assertTrue(server.takeRequest(4, TimeUnit.SECONDS) != null) }
                job.cancelAndJoin()
            }
        }
    }

    @Test fun identicalSourcesShareAuthenticationAndOneCanBeRemovedIndependently() = runBlocking(Dispatchers.IO) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"js\":{\"token\":\"synthetic-token\"}}"))
            server.enqueue(MockResponse().setBody("{\"js\":{\"status\":0}}"))
            server.enqueue(MockResponse().setBody("{\"js\":[]}"))
            val first = PlaylistSource("first", "First alias", "", providerKind = "stalker",
                stalker = StalkerConfig(server.url("/portal.php").toString(), "00:11:22:33:44:55"))
            val second = first.copy(id = "second", name = "Second alias")
            LiveTvPreferences.installPersistence(load = { emptyMap() }, save = {})
            LiveTvPreferences.updatePlaylists { listOf(first, second) }
            val firstClient = StalkerGateway.client(first)
            val secondClient = StalkerGateway.client(second)
            assertSame(firstClient, secondClient)
            firstClient.authenticate(); secondClient.authenticate()
            assertEquals(2, server.requestCount)
            LiveTvPreferences.updatePlaylists { listOf(second) }
            StalkerGateway.invalidateSource(first.id)
            assertTrue(secondClient.liveGroups().isEmpty())
            assertEquals(3, server.requestCount)
            StalkerGateway.invalidateSource(second.id)
        }
    }

    @Test fun coldBackgroundProfileIsReadWithoutChangingTheSavedSelectionOrAnActiveOne() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("nuvio_profile_cache", android.content.Context.MODE_PRIVATE)
        val original = preferences.getString("profile_payload", null)
        val payload = "{\"userId\":\"synthetic\",\"activeProfileIndex\":2,\"profiles\":[{\"profileIndex\":1},{\"profileIndex\":2}]}"
        preferences.edit().putString("profile_payload", payload).commit()
        val field = VpnPlaybackProtection::class.java.getDeclaredField("activeUserProfile").apply { isAccessible = true }
        try {
            field.setInt(null, 0)
            assertFailsWith<java.io.IOException> { VpnPlaybackProtection.requireUniformHttpRouting() }
            val restored = ProfileStorage.savedActiveProfileForBackground(context)
            assertEquals(2, restored)
            assertEquals(payload, preferences.getString("profile_payload", null))
            VpnPlaybackProtection.initializeActiveUserProfile(restored)
            assertEquals(2, VpnPlaybackProtection.activeUserProfile())
            VpnPlaybackProtection.initializeActiveUserProfile(1)
            assertEquals(2, VpnPlaybackProtection.activeUserProfile())
        } finally {
            VpnPlaybackProtection.setActiveUserProfile(1)
            if (original == null) preferences.edit().remove("profile_payload").commit()
            else preferences.edit().putString("profile_payload", original).commit()
        }
    }

    @Test fun portalAndStreamMustStayOnTheSameRouteIncludingRedirectedSegments() {
        assertTrue(consistentStalkerRoutes("OFF:null:1", "https://portal.example/c/", "http://cdn.example/live"))
        assertTrue(consistentStalkerRoutes("HTTP_HTTPS:synthetic:1", "https://portal.example/c/", "http://cdn.example/live"))
        assertTrue(consistentStalkerRoutes("APP_WIDE:synthetic:1", "https://portal.example/c/", "https://cdn.example/live"))
        assertEquals(false, consistentStalkerRoutes("HTTP_HTTPS:synthetic:1", "https://portal.example/c/", "http://127.0.0.1/live"))
        assertEquals(false, consistentStalkerRoutes("APP_WIDE:synthetic:1", "http://localhost/portal.php", "https://cdn.example/live"))
    }

    @Test fun lastStreamReusePersistsTheOpaqueReferenceAndNeverTheResolvedUrlOrHeaders() {
        val context = RuntimeEnvironment.getApplication()
        StreamLinkCacheStorage.initialize(context)
        val ref = StalkerStreamRef(StalkerContentKind.VOD, "movie-123", "ffmpeg http://localhost/vod/opaque_",
            sourceIdentity = "synthetic-source-identity")
        val key = "movie|stalker-private-cache"
        try {
            StreamLinkCacheRepository.save(key, "https://cdn.example/movie?token=must-not-persist", "Movie", "On Demand", "livetv-ondemand",
                requestHeaders = mapOf("Authorization" to "must-not-persist"), responseHeaders = mapOf("Set-Cookie" to "must-not-persist"),
                stalkerSourceId = "portal-source", stalkerStreamJson = ref.encode())
            StreamLinkCacheStorage.initialize(context) // The same durable entry after reopening storage.
            val entry = StreamLinkCacheRepository.getValid(key, 86_400_000)!!
            assertTrue(entry.url.startsWith("stalker:"))
            assertEquals(ref, StalkerStreamRef.decode(entry.stalkerStreamJson!!))
            assertEquals("portal-source", entry.stalkerSourceId)
            assertTrue(entry.requestHeaders.isEmpty()); assertTrue(entry.responseHeaders.isEmpty())
            val saved = context.getSharedPreferences("nuvio_stream_link_cache", android.content.Context.MODE_PRIVATE).all.values.joinToString()
            assertTrue(!saved.contains("must-not-persist") && !saved.contains("cdn.example"))
        } finally { StreamLinkCacheRepository.remove(key) }
    }

    @Test fun ordinaryStreamReuseRetainsItsExistingHeaders() {
        StreamLinkCacheStorage.initialize(RuntimeEnvironment.getApplication())
        val key = "movie|ordinary-cache"
        try {
            StreamLinkCacheRepository.save(key, "https://provider.example/movie.mp4", "Movie", "Addon", "addon:test",
                requestHeaders = mapOf("User-Agent" to "ordinary-player"))
            val entry = StreamLinkCacheRepository.getValid(key, 86_400_000)!!
            assertEquals("https://provider.example/movie.mp4", entry.url)
            assertEquals("ordinary-player", entry.requestHeaders["User-Agent"])
            assertNull(entry.stalkerStreamJson)
        } finally { StreamLinkCacheRepository.remove(key) }
    }

    @Test fun extensionlessHlsIsIdentifiedWithABoundedRangeRequest() = runBlocking(Dispatchers.IO) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream"))
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-ENDLIST\n"))
            val policy = StalkerMediaPolicy(StalkerMediaAuthorization(server.url("/portal.php").toString(), emptyMap()), stalkerRouteIdentity()) { true }
            assertEquals("hls", stalkerProbeStreamType(server.url("/play.php?id=synthetic").toString(), policy))
            assertEquals("HEAD", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
            val ranged = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("GET", ranged.method)
            assertEquals("bytes=0-8191", ranged.getHeader("Range"))
        }
    }

    @Test fun knownHlsLinksDoNotMakeAProbeRequest() = runBlocking(Dispatchers.IO) {
        MockWebServer().use { server ->
            val policy = StalkerMediaPolicy(StalkerMediaAuthorization(server.url("/portal.php").toString(), emptyMap()), stalkerRouteIdentity()) { true }
            assertEquals("hls", stalkerProbeStreamType(server.url("/live.m3u8").toString(), policy))
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun aRateLimitedHeadProbeDoesNotSendAnotherGetRequest() = runBlocking(Dispatchers.IO) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429))
            val policy = StalkerMediaPolicy(StalkerMediaAuthorization(server.url("/portal.php").toString(), emptyMap()), stalkerRouteIdentity()) { true }
            assertNull(stalkerProbeStreamType(server.url("/play.php").toString(), policy))
            assertEquals(1, server.requestCount)
            assertEquals("HEAD", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
        }
    }

    @Test fun knownProgressiveHeadResponsesDoNotTriggerAGet() = runBlocking(Dispatchers.IO) {
        MockWebServer().use { server ->
            val policy = StalkerMediaPolicy(StalkerMediaAuthorization(server.url("/portal.php").toString(), emptyMap()), stalkerRouteIdentity()) { true }
            for (type in listOf("video/mp2t", "video/mp4")) {
                server.enqueue(MockResponse().setHeader("Content-Type", type))
                assertNull(stalkerProbeStreamType(server.url("/play.php").toString(), policy))
                assertEquals("HEAD", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun ignoredRangeBodyIsCancelledBeforeCloseCanDrainMoreThanEightKibibytes() {
        for (headerType in listOf("application/octet-stream", "application/vnd.apple.mpegurl")) {
            val call = okhttp3.OkHttpClient().newCall(Request.Builder().url("https://provider.example/play.php").build())
            val pending = okio.Buffer().writeUtf8("#EXTM3U\n" + "x".repeat(256 * 1024))
            val length = pending.size
            var wireBytes = 0L
            var closedAfterCancellation = false
            val source = object : okio.Source {
                override fun read(sink: okio.Buffer, byteCount: Long): Long = pending.read(sink, byteCount).also { if (it > 0) wireBytes += it }
                override fun timeout(): okio.Timeout = okio.Timeout.NONE
                override fun close() {
                    closedAfterCancellation = call.isCanceled()
                    if (!call.isCanceled()) {
                        // Model OkHttp's extra live-body discard when an uncancelled exchange closes.
                        val discarded = okio.Buffer()
                        while (read(discarded, 8192) > 0) discarded.clear()
                    }
                }
            }.let { it.buffer() }
            val body = object : okhttp3.ResponseBody() {
                override fun contentType(): okhttp3.MediaType? = null
                override fun contentLength(): Long = length
                override fun source(): okio.BufferedSource = source
            }
            val response = okhttp3.Response.Builder().request(call.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("Range ignored").header("Content-Type", headerType).body(body).build()
            assertEquals("hls", readStalkerProbeBody(call, response))
            assertTrue(call.isCanceled()); assertTrue(closedAfterCancellation)
            assertTrue(wireBytes <= 8192, "Probe read $wireBytes bytes from an ignored-range response")
            assertEquals(if (headerType == "application/octet-stream") 8192L else 0L, wireBytes)
        }
    }

    @Test fun genericAndRestoredVodSelectionsResolveFreshLinksWithoutPersistingASession() = runBlocking(Dispatchers.IO) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"js\":{\"token\":\"synthetic-session-token\"}}"))
            server.enqueue(MockResponse().setBody("{\"js\":{\"status\":0}}"))
            server.enqueue(MockResponse().setBody("{\"js\":{\"cmd\":\"ffmpeg https://cdn.example/first.m3u8\"}}"))
            server.enqueue(MockResponse().setBody("{\"js\":{\"cmd\":\"ffmpeg https://cdn.example/renewed.m3u8\"}}"))
            val source = PlaylistSource("generic-vod", "Portal", "", providerKind = "stalker",
                stalker = StalkerConfig(server.url("/portal.php").toString(), "00:11:22:33:44:55"))
            LiveTvPreferences.installPersistence(load = { emptyMap() }, save = {})
            LiveTvPreferences.updatePlaylists { listOf(source) }
            val ref = StalkerStreamRef(StalkerContentKind.VOD, "movie-1", "ffmpeg http://localhost/vod/opaque_",
                sourceIdentity = StalkerGateway.sourceIdentity(source))
            val original = StreamItem(url = StalkerGateway.locator(source.id, ref), addonName = "On Demand", addonId = "livetv-ondemand",
                stalkerSourceId = source.id, stalkerStream = ref)
            val first = OnDemandPlayback.resolveStreamForPlayback(original, "movie-video")
            assertEquals("https://cdn.example/first.m3u8", first.url)
            assertTrue(first.stalkerMediaPolicy != null)
            assertTrue(first.behaviorHints.proxyHeaders?.request.orEmpty().isEmpty())
            val restored = original.copy(stalkerStream = StalkerStreamRef.decode(ref.encode()))
            val second = OnDemandPlayback.resolveStreamForPlayback(restored, "movie-video")
            assertEquals("https://cdn.example/renewed.m3u8", second.url)
            assertEquals(4, server.requestCount) // One shared authentication, one create_link per selection.
            assertEquals(StalkerGateway.locator(source.id, ref), original.url)
            StalkerGateway.invalidateSource(source.id)
        }
    }

    @Test fun anIndependentHttpsKeyDoesNotPreventHttpMasterSegments() = runBlocking(Dispatchers.IO) {
        val tls = testTls()
        MockWebServer().use { master -> MockWebServer().use { keys ->
            keys.useHttps(tls.first.socketFactory, false)
            keys.enqueue(MockResponse().setBody("synthetic-key"))
            master.enqueue(MockResponse().setBody("segment"))
            val policy = StalkerMediaPolicy(StalkerMediaAuthorization(master.url("/portal.php").toString(), emptyMap()), stalkerRouteIdentity()) { true }
            val calls = VpnHttpClient.wrap(StalkerMediaHttp.client(policy).newBuilder().sslSocketFactory(tls.first.socketFactory, tls.second).build())
            calls.newCall(Request.Builder().url(keys.url("/key").newBuilder().host("127.0.0.1").build()).build()).execute().close()
            calls.newCall(Request.Builder().url(master.url("/segment.ts")).build()).execute().use { assertTrue(it.isSuccessful) }
            assertEquals(1, keys.requestCount); assertEquals(1, master.requestCount)
        } }
    }

    @Test fun aRedirectThatUpgradesToHttpsCannotDowngradeAgain() = runBlocking(Dispatchers.IO) {
        val tls = testTls()
        MockWebServer().use { plain -> MockWebServer().use { secure ->
            secure.useHttps(tls.first.socketFactory, false)
            plain.enqueue(MockResponse().setResponseCode(302).setHeader("Location", secure.url("/upgrade").newBuilder().host("127.0.0.1").build()))
            secure.enqueue(MockResponse().setResponseCode(302).setHeader("Location", plain.url("/must-not-arrive")))
            val policy = StalkerMediaPolicy(StalkerMediaAuthorization(plain.url("/portal.php").toString(), emptyMap()), stalkerRouteIdentity()) { true }
            val calls = VpnHttpClient.wrap(StalkerMediaHttp.client(policy).newBuilder().sslSocketFactory(tls.first.socketFactory, tls.second).build())
            assertFailsWith<java.io.IOException> { calls.newCall(Request.Builder().url(plain.url("/start")).build()).execute() }
            assertEquals(1, plain.requestCount); assertEquals(1, secure.requestCount)
        } }
    }

    @Test fun anHttpsRootRefusesAllPlainHttpChildren() = runBlocking(Dispatchers.IO) {
        val policy = StalkerMediaPolicy(StalkerMediaAuthorization("https://portal.example/portal.php", emptyMap()), stalkerRouteIdentity(),
            requireHttps = true) { true }
        val failure = assertFailsWith<StalkerException> { policy.headersFor("http://cdn.example/segment.ts") }
        assertEquals(StalkerError.LINK_UNAVAILABLE, failure.error)
    }

    /** Existing, synthetic self-signed loopback test certificate; never used by production. */
    private fun testTls(): Pair<javax.net.ssl.SSLContext, javax.net.ssl.X509TrustManager> {
        val password = "changeit".toCharArray()
        val store = java.security.KeyStore.getInstance("PKCS12").apply {
            requireNotNull(StalkerProtectedPlaybackTest::class.java.getResourceAsStream("/stalker-redirect-test.p12")).use { load(it, password) }
        }
        val managers = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val trust = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().first()
        val context = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(managers.keyManagers, arrayOf(trust), null) }
        return context to trust
    }
}
