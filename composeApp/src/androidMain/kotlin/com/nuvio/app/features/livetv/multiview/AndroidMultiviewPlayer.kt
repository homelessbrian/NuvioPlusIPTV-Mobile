package com.nuvio.app.features.livetv.multiview

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.nuvio.app.core.vpn.awaitVpnForPlayback
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.player.LivePlayRequest
import com.nuvio.app.features.livetv.player.LiveTvPlayback
import com.nuvio.app.features.livetv.ui.LiveTvViewState
import com.nuvio.app.features.player.playbackAudioAttributes
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.vpn.android.VpnPlaybackProtection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One independently owned player; no global single-channel launch, caption, or fallback state. */
@OptIn(UnstableApi::class)
internal class AndroidMultiviewPlayer(
    context: Context,
    private val mayPlayChannel: (LiveChannel) -> Boolean = multiviewChannelPermissionGuard(),
) : MultiviewPlayer {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(MultiviewTileState())
    override val state: StateFlow<MultiviewTileState> = mutableState.asStateFlow()
    private var channel: LiveChannel? = null
    private var requestOverride: LivePlayRequest? = null
    private var triedTs = false
    private var muted = true
    private var paused = false
    private var lifecycleStarted = false
    private var focusSuspended = false
    private var released = false
    private var prepareJob: Job? = null
    private var generation = 0L
    private var engine: ExoPlayer? = null
    private var surface: MultiviewVideoView? = null
    private val focus = MultiviewAudioFocus.session(this.context)
    private val vpnSubscription = VpnPlaybackProtection.observeSettings {
        main.post {
            if (!released) {
                stopEngine()
                startIfVisible()
            }
        }
    }

    init { focus.register(this) }

    override fun play(channel: LiveChannel) {
        if (released) return
        stopEngine()
        this.channel = channel
        requestOverride = null
        triedTs = false
        focusSuspended = false
        mutableState.value = MultiviewTileState()
        startIfVisible()
    }

    override fun setMuted(muted: Boolean) {
        if (released) return
        this.muted = muted
        if (muted) {
            if (!canPlay()) {
                stopEngine()
                return
            }
            // PCM volume is applied before the old audio track is disabled or focus is released.
            silence()
            focus.abandon(this)
        } else {
            // Explicit selection also resumes the session after a call or another focus loss.
            focus.resumeSession()
            if (engine != null) applyAudioState() else startIfVisible()
        }
    }

    override fun setPaused(paused: Boolean) {
        if (released) return
        this.paused = paused
        if (paused) stopEngine() else startIfVisible()
    }

    fun setLifecycleStarted(started: Boolean) {
        if (released) return
        lifecycleStarted = started
        if (!started) stopEngine() else {
            resumeAfterAudioFocusLoss()
            startIfVisible()
        }
    }

    fun attachSurface(view: MultiviewVideoView) {
        if (released || surface === view) return
        surface?.bind(null)
        surface = view
        view.bind(engine, secure = channel?.drm != null)
        startIfVisible()
    }

    fun detachSurface(view: MultiviewVideoView) {
        view.bind(null)
        if (surface === view) {
            surface = null
            stopEngine()
        }
    }

    override fun release() {
        if (released) return
        released = true
        stopEngine()
        surface = null
        channel = null
        requestOverride = null
        vpnSubscription.close()
        focus.unregister(this)
        scope.cancel()
    }

    internal fun silence() {
        engine?.let { player ->
            player.volume = 0f
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        }
    }

    internal fun suspendForAudioFocusLoss() {
        if (released) return
        focusSuspended = true
        stopEngine()
        mutableState.value = MultiviewTileState(error = "Playback paused by another app. Select a tile to resume.")
    }

    internal fun resumeAfterAudioFocusLoss() {
        if (released || !focusSuspended) return
        focusSuspended = false
        mutableState.value = MultiviewTileState()
        startIfVisible()
    }

    private fun canPlay() = !released && !paused && lifecycleStarted && !focusSuspended && surface != null &&
        channel?.let(mayPlayChannel) == true

    private fun startIfVisible() {
        val selected = channel ?: return
        if (!canPlay()) {
            if (engine != null || prepareJob?.isActive == true) stopEngine()
            return
        }
        if (engine != null || prepareJob?.isActive == true) return
        val token = generation
        val request = requestOverride ?: LiveTvPlayback.requestFor(selected)
        mutableState.value = MultiviewTileState(isLoading = true)
        prepareJob = scope.launch {
            try {
                val source = multiviewMediaSource(context, selected, request)
                // Re-check when profile/scope settings change while the connection is prepared.
                do {
                    val revision = VpnPlaybackProtection.settingsRevision()
                    awaitVpnForPlayback(context, request.url)
                    selected.drm?.license?.trim()?.takeIf {
                        it.startsWith("http://", true) || it.startsWith("https://", true)
                    }?.let { awaitVpnForPlayback(context, it) }
                } while (revision != VpnPlaybackProtection.settingsRevision())
                if (token != generation || !canPlay()) return@launch
                val player = buildPlayer()
                engine = player
                player.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        if (engine !== player) return
                        mutableState.value = MultiviewTileState(
                            isLoading = player.isLoading || player.playbackState == Player.STATE_BUFFERING,
                            isPlaying = player.isPlaying,
                        )
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (engine !== player) return
                        // Retry only this channel as TS; do not alter shared fallback bookkeeping.
                        val ts = if (!triedTs && selected.drm == null && error.errorCode in 2000..3999 &&
                            multiviewHttpStatus(error) !in listOf(401, 403, 429, 456, 458, 509)) {
                            LiveTvPlayback.tsLinkFor(request.url)
                        } else null
                        if (ts != null && ts != request.url) {
                            triedTs = true
                            requestOverride = request.copy(url = ts, streamType = null)
                            stopEngine()
                            startIfVisible()
                        } else {
                            stopEngine()
                            mutableState.value = MultiviewTileState(error = multiviewPlaybackError(error))
                        }
                    }
                })
                surface?.bind(player, secure = selected.drm != null)
                player.setMediaSource(source)
                applyAudioState()
                if (engine !== player) return@launch
                player.playWhenReady = true
                player.prepare()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (token != generation || released) return@launch
                stopEngine()
                mutableState.value = MultiviewTileState(error =
                    (failure as? MultiviewSourceException)?.tileMessage
                        ?: "Playback could not start. Check the channel and VPN settings.",
                )
            }
        }
    }

    private fun applyAudioState() {
        val player = engine ?: return
        if (!canPlay()) {
            stopEngine()
            return
        }
        if (muted) {
            silence()
            focus.abandon(this)
        } else if (focus.acquire(this)) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build()
            player.volume = 1f
        } else {
            suspendForAudioFocusLoss()
        }
    }

    private fun stopEngine() {
        generation++
        prepareJob?.cancel()
        prepareJob = null
        val previous = engine
        engine = null // Stale listeners cannot overwrite this tile's next request.
        previous?.volume = 0f
        surface?.bind(null)
        focus.abandon(this)
        previous?.run {
            playWhenReady = false
            stop()
            clearMediaItems()
            clearVideoSurface()
            release()
        }
        mutableState.value = mutableState.value.copy(isLoading = false, isPlaying = false)
    }

    private fun buildPlayer(): ExoPlayer {
        val lowMemory = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
        val renderers = object : DefaultRenderersFactory(context) {
            @Suppress("DEPRECATION")
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    // Passthrough can ignore volume. Decode to PCM so mute ordering is reliable.
                    .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build().apply { setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED) }
        }.setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_000, 8_000, 500, 1_000)
            .setBackBuffer(0, false)
            .setTargetBufferBytes((if (lowMemory) 4 else 8) * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        return ExoPlayer.Builder(context, renderers)
            .setAudioAttributes(playbackAudioAttributes, false)
            .setHandleAudioBecomingNoisy(false)
            .setLoadControl(loadControl)
            .build().apply {
                volume = 0f
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setMaxVideoSize(1280, 720)
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .setAudioOffloadPreferences(TrackSelectionParameters.AudioOffloadPreferences.Builder()
                        .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED).build())
                    .build()
            }
    }
}

/** Native lifecycle callbacks may run before Compose refreshes a backgrounded selection. */
internal fun multiviewChannelPermissionGuard(): (LiveChannel) -> Boolean {
    fun profileId() = ProfileRepository.state.value.activeProfile?.profileIndex ?: ProfileRepository.activeProfileId
    val creatingProfile = profileId()
    return { selected ->
        creatingProfile == profileId() && LiveTvViewState.list.value.visible.any { it == selected }
    }
}

internal fun multiviewHttpStatus(failure: Throwable): Int? {
    var current: Throwable? = failure
    repeat(8) {
        val cause = current ?: return null
        if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
        current = cause.cause?.takeUnless { it === cause }
    }
    return null
}

internal fun multiviewPlaybackError(failure: Throwable): String = when (multiviewHttpStatus(failure)) {
    429, 456, 458, 509 -> "The provider's connection limit was reached. Try fewer panes."
    401, 403 -> "The provider refused this channel. Check your subscription or choose another channel."
    else -> when ((failure as? PlaybackException)?.errorCode ?: 0) {
        in 4000..4999 -> "This stream cannot be decoded. Try fewer panes or another channel."
        in 6000..6999 -> "This protected stream could not be opened on this device."
        else -> "This channel could not be played. Select it again to retry."
    }
}
