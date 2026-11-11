package com.nuvio.app.features.livetv.multiview

import android.app.Application
import android.media.AudioManager
import android.os.Looper
import android.util.Base64
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.audio.AudioFocusRequestCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.app.features.livetv.model.DrmInfo
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.player.LiveTvPlayback
import com.nuvio.app.features.player.PlayerPlaybackNetworking
import com.nuvio.vpn.android.VpnHttpClient
import io.sentry.Breadcrumb
import io.sentry.NoOpTransportFactory
import io.sentry.Sentry
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@androidx.annotation.OptIn(UnstableApi::class)
class MultiviewAndroidPlayerTest {
    @Test
    fun clearKeyHexPairsBecomeAValidTemporaryKeySet() {
        val bytes = assertNotNull(multiviewClearKeyJson("00112233445566778899aabbccddeeff:ffeeddccbbaa99887766554433221100"))
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("temporary", json.getString("type"))
        val keys = json.getJSONArray("keys")
        assertEquals(1, keys.length())
        assertEquals("oct", keys.getJSONObject(0).getString("kty"))
        assertEquals("ABEiM0RVZneImaq7zN3u_w", keys.getJSONObject(0).getString("kid"))
        assertEquals("_-7dzLuqmYh3ZlVEMyIRAA", keys.getJSONObject(0).getString("k"))
    }

    @Test
    fun clearKeyAcceptsBase64PairsAndJsonWithoutChangingKeyBytes() {
        val kid = Base64.encodeToString(ByteArray(16) { it.toByte() }, Base64.NO_WRAP)
        val key = Base64.encodeToString(ByteArray(16) { (255 - it).toByte() }, Base64.NO_WRAP)
        val paired = JSONObject(assertNotNull(multiviewClearKeyJson("$kid:$key")).toString(Charsets.UTF_8))
        val suppliedJson = """{"keys":[{"kty":"oct","kid":"$kid","k":"$key"}],"type":"temporary"}"""
        val supplied = JSONObject(assertNotNull(multiviewClearKeyJson(suppliedJson)).toString(Charsets.UTF_8))
        assertEquals(paired.toString(), supplied.toString())
        assertEquals(16, Base64.decode(supplied.getJSONArray("keys").getJSONObject(0).getString("kid"), Base64.URL_SAFE).size)
        assertEquals(16, Base64.decode(supplied.getJSONArray("keys").getJSONObject(0).getString("k"), Base64.URL_SAFE).size)
    }

    @Test
    fun clearKeyRejectsPartialMalformedAndOversizedKeySets() {
        val valid = "00112233445566778899aabbccddeeff:ffeeddccbbaa99887766554433221100"
        val invalid = listOf(
            "", ":", "0011:2233", "$valid,", "$valid:extra", "{broken",
            """{"keys":[]}""",
            """{"keys":[{"kty":"RSA","kid":"ABEiM0RVZneImaq7zN3u_w","k":"_-7dzLuqmYh3ZlVEMyIRAA"}]}""",
            List(33) { valid }.joinToString(","),
        )
        invalid.forEach { assertNull(multiviewClearKeyJson(it), "Malformed key data must never reach MediaDrm") }
    }

    @Test
    fun unsupportedProtocolsReturnFixedErrorsWithoutExposingAnAddress() {
        val context = RuntimeEnvironment.getApplication()
        for (scheme in listOf("rtsp", "udp", "rtmp", "file", "content")) {
            val selected = channel("$scheme://test-user:test-password@stream.example.invalid/live?token=test-token")
            val failure = assertFailsWith<MultiviewSourceException> {
                multiviewMediaSource(context, selected, LiveTvPlayback.requestFor(selected))
            }
            assertEquals(if (scheme == "rtsp") "RTSP streams are unavailable in Multiview."
                else "This stream format is unavailable in Multiview.", failure.tileMessage)
            assertNull(failure.message)
            assertFalse(failure.tileMessage.contains("test-"))
            assertFalse(failure.tileMessage.contains("example.invalid"))
        }
    }

    @Test
    fun unknownDrmCannotFallThroughToUnprotectedPlayback() {
        val context = RuntimeEnvironment.getApplication()
        val selected = channel("https://stream.example.invalid/manifest.mpd").copy(
            drm = DrmInfo("unknown", "https://license.example.invalid/?token=test-token"),
        )
        val failure = assertFailsWith<MultiviewSourceException> {
            multiviewMediaSource(context, selected, LiveTvPlayback.requestFor(selected))
        }
        assertEquals("This DRM scheme is unavailable in Multiview.", failure.tileMessage)
        assertNull(failure.message)
    }

    @Test
    fun providerConnectionLimitsAndAuthorizationErrorsHaveCredentialFreeActions() {
        val spec = DataSpec.Builder().setUri("https://stream.example.invalid/live/test-user/test-password/123?token=test-token").build()
        for (status in listOf(429, 456, 458, 509, 401, 403)) {
            val response = HttpDataSource.InvalidResponseCodeException(status, "test-token", null,
                emptyMap(), spec, ByteArray(0))
            // Media3 wraps the HTTP exception; causes are inspected without exposing their text.
            val message = multiviewPlaybackError(IllegalStateException("test-password", response))
            assertEquals(if (status in listOf(429, 456, 458, 509))
                "The provider's connection limit was reached. Try fewer panes."
                else "The provider refused this channel. Check your subscription or choose another channel.", message)
            assertFalse(message.contains("test-"))
            assertFalse(message.contains("example.invalid"))
        }
    }

    @Test
    fun pausedBackgroundHiddenAndReleasedPlayersNeverRequestTheChannel() {
        MockWebServer().use { server ->
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val selected = channel(server.url("/live/test-user/test-password/123.m3u8").toString())
            val player = AndroidMultiviewPlayer(context, mayPlayChannel = { true })
            val view = MultiviewVideoView(context)
            try {
                player.setPaused(true)
                player.setLifecycleStarted(true)
                player.attachSurface(view)
                player.play(selected)
                drainMain()
                assertIdle(player)

                player.setLifecycleStarted(false)
                player.setPaused(false)
                player.play(selected)
                drainMain()
                assertIdle(player)

                player.detachSurface(view)
                player.setLifecycleStarted(true)
                player.play(selected)
                drainMain()
                assertIdle(player)

                player.release()
                player.release()
                player.attachSurface(view)
                player.setMuted(false)
                player.setPaused(false)
                player.play(selected)
                drainMain()
                assertIdle(player)
                assertEquals(0, server.requestCount)
            } finally {
                player.release()
            }
        }
    }

    @Test
    fun visiblePlayerSurfacesTheSanitizedProtocolFailure() {
        val context = RuntimeEnvironment.getApplication()
        val player = AndroidMultiviewPlayer(context, mayPlayChannel = { true })
        try {
            player.setLifecycleStarted(true)
            player.attachSurface(MultiviewVideoView(context))
            player.play(channel("rtsp://test-user:test-password@stream.example.invalid/live?token=test-token"))
            drainMain()
            assertEquals("RTSP streams are unavailable in Multiview.", player.state.value.error)
            assertFalse(player.state.value.isLoading)
            assertFalse(player.state.value.isPlaying)
        } finally {
            player.release()
        }
    }

    @Test
    fun permissionsRevokedWhileBackgroundedPreventTheSavedRequestFromReopening() {
        MockWebServer().use { server ->
            server.start()
            val context = RuntimeEnvironment.getApplication()
            var allowed = true
            val player = AndroidMultiviewPlayer(context, mayPlayChannel = { allowed })
            try {
                player.setPaused(true)
                player.setLifecycleStarted(true)
                player.attachSurface(MultiviewVideoView(context))
                player.play(channel(server.url("/live/test-user/test-password/123.m3u8").toString()))
                drainMain()
                assertIdle(player)
                player.setLifecycleStarted(false)
                player.setPaused(false)
                allowed = false
                // The native callback precedes Compose's next allowed-list/profile render.
                player.setLifecycleStarted(true)
                drainMain()
                assertIdle(player)
                assertEquals(0, server.requestCount)
            } finally {
                player.release()
            }
        }
    }

    @Test
    fun explicitUnmuteOrResumeReleasesAnExistingEngineWhenPermissionWasRevoked() {
        MockWebServer().use { server ->
            server.start()
            for (unmute in listOf(true, false)) {
                val context = RuntimeEnvironment.getApplication()
                var allowed = true
                val player = AndroidMultiviewPlayer(context, mayPlayChannel = { allowed })
                val fake = RecordingExoPlayer()
                val view = MultiviewVideoView(context)
                try {
                    // Establish an allowed retained channel without opening the real transport,
                    // then supply an already active Exo owner to simulate the native/UI race.
                    player.setPaused(true)
                    player.setLifecycleStarted(true)
                    player.attachSurface(view)
                    player.play(channel(server.url("/live/test-user/test-password/123.m3u8").toString()))
                    ReflectionHelpers.setField(player, "paused", false)
                    ReflectionHelpers.setField(player, "engine", fake.player)
                    view.bind(fake.player)
                    allowed = false
                    if (unmute) player.setMuted(false) else player.setPaused(false)
                    assertTrue(fake.released)
                    assertFalse(fake.playWhenReady)
                    assertEquals(0f, fake.volume)
                    assertTrue(fake.stopped)
                    assertTrue(fake.mediaCleared)
                    assertNull(fake.texture)
                    assertEquals(0, server.requestCount)
                } finally {
                    player.release()
                }
            }
        }
    }

    @Test
    fun videoRemainsVisibleBehindACoverUntilAFrameAndCoverResetsOnDetach() {
        val view = MultiviewVideoView(RuntimeEnvironment.getApplication())
        val fake = RecordingExoPlayer()
        val cover = view.getChildAt(view.childCount - 1)
        view.bind(fake.player)
        val texture = assertNotNull(fake.texture)
        assertEquals(View.VISIBLE, texture.visibility)
        assertEquals(View.VISIBLE, cover.visibility)
        fake.listeners.toList().forEach { it.onRenderedFirstFrame() }
        assertEquals(View.GONE, cover.visibility)
        assertEquals(View.VISIBLE, texture.visibility)
        view.bind(null)
        assertEquals(View.VISIBLE, cover.visibility)
        assertTrue(fake.listeners.isEmpty())
        assertNull(fake.texture)

        view.bind(fake.player, secure = true)
        val surface = assertNotNull(fake.surface)
        assertEquals(View.VISIBLE, surface.visibility)
        assertEquals(View.VISIBLE, cover.visibility)
        fake.listeners.toList().forEach { it.onRenderedFirstFrame() }
        assertEquals(View.GONE, cover.visibility)
        assertEquals(View.VISIBLE, surface.visibility)
        view.bind(null)
        assertEquals(View.VISIBLE, cover.visibility)
        assertNull(surface.parent)
        assertNull(fake.surface)
    }

    @Test
    fun callbacksFromAbandonedFocusRequestsCannotPauseANewlyAcquiredSession() {
        val context = RuntimeEnvironment.getApplication()
        val first = AndroidMultiviewPlayer(context, mayPlayChannel = { true })
        val second = AndroidMultiviewPlayer(context, mayPlayChannel = { true })
        val focus = MultiviewAudioFocus.session(context)
        try {
            assertTrue(focus.acquire(first))
            val oldRequest = ReflectionHelpers.getField<AudioFocusRequestCompat>(focus, "request")
            focus.abandon(first)
            assertTrue(focus.acquire(second))
            oldRequest.onAudioFocusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            drainMain()
            assertNull(first.state.value.error)
            assertNull(second.state.value.error)
            val currentRequest = ReflectionHelpers.getField<AudioFocusRequestCompat>(focus, "request")
            currentRequest.onAudioFocusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            drainMain()
            assertEquals("Playback paused by another app. Select a tile to resume.", first.state.value.error)
            assertEquals(first.state.value.error, second.state.value.error)
        } finally {
            first.release()
            second.release()
        }
    }

    @Test
    fun multiviewScopedClientSuppressesCredentialBreadcrumbsWhileDefaultKeepsItsBehavior() {
        val breadcrumbs = mutableListOf<Breadcrumb>()
        Sentry.init { options ->
            options.dsn = "https://public@example.invalid/1"
            options.setEnableUncaughtExceptionHandler(false)
            options.setTransportFactory(NoOpTransportFactory.getInstance())
            options.setBeforeBreadcrumb { breadcrumb, _ -> breadcrumbs += breadcrumb; breadcrumb }
        }
        try {
            MockWebServer().use { server ->
                server.start()
                fun request(recordDiagnostics: Boolean) {
                    server.enqueue(MockResponse().setBody("stream"))
                    val factory = PlayerPlaybackNetworking.createHttpDataSourceFactory(recordDiagnostics = recordDiagnostics) as OkHttpDataSource.Factory
                    val protectedCalls = ReflectionHelpers.getField<Call.Factory>(factory, "callFactory")
                    assertTrue(protectedCalls is VpnHttpClient)
                    // Exercise the exact scoped baseline while isolating this diagnostics test
                    // from VPN consent/native setup. Production retains the verified wrapper.
                    val client = ReflectionHelpers.getField<OkHttpClient>(protectedCalls, "baseline")
                    client.newCall(Request.Builder().url(server.url("/live/test-user/test-password/123.m3u8?token=test-token")).build())
                        .execute().use { it.body?.string() }
                }
                request(recordDiagnostics = true)
                val baselineCount = breadcrumbs.count { it.category == "http.client" }
                assertEquals(1, baselineCount)
                request(recordDiagnostics = false)
                assertEquals(baselineCount, breadcrumbs.count { it.category == "http.client" })
                request(recordDiagnostics = true)
                assertEquals(baselineCount + 1, breadcrumbs.count { it.category == "http.client" })
            }
        } finally {
            Sentry.close()
        }
    }

    private fun assertIdle(player: MultiviewPlayer) {
        assertFalse(player.state.value.isPlaying)
        assertFalse(player.state.value.isLoading)
        assertNull(player.state.value.error)
    }

    private fun drainMain() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    private fun channel(url: String) = LiveChannel(
        key = "fixture", sourceId = "fixture", sourceName = "Fixture", name = "Fixture",
        tvgId = null, tvgName = null, logo = null, groupId = "fixture:group", group = "Fixture",
        number = 1, url = url,
    )

    private class RecordingExoPlayer : InvocationHandler {
        val listeners = mutableSetOf<Player.Listener>()
        var released = false
        var playWhenReady = true
        var volume = 1f
        var stopped = false
        var mediaCleared = false
        var texture: TextureView? = null
        var surface: SurfaceView? = null
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java), this) as ExoPlayer

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "addListener" -> { listeners += args!![0] as Player.Listener; null }
            "removeListener" -> { listeners -= args!![0] as Player.Listener; null }
            "getVideoSize" -> VideoSize(1920, 1080)
            "setVideoTextureView" -> { texture = args!![0] as TextureView; null }
            "clearVideoTextureView" -> { texture = null; null }
            "setVideoSurfaceView" -> { surface = args!![0] as SurfaceView; null }
            "clearVideoSurfaceView" -> { surface = null; null }
            "setVolume" -> { volume = args!![0] as Float; null }
            "setPlayWhenReady" -> { playWhenReady = args!![0] as Boolean; null }
            "stop" -> { stopped = true; null }
            "clearMediaItems" -> { mediaCleared = true; null }
            "clearVideoSurface" -> null
            "release" -> { released = true; null }
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "RecordingExoPlayer"
            else -> error("Unexpected player method: ${method.name}")
        }
    }
}
