package com.nuvio.app.features.livetv.multiview

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.audio.AudioFocusRequestCompat
import androidx.media3.common.audio.AudioManagerCompat
import androidx.media3.common.util.UnstableApi
import com.nuvio.app.features.player.playbackAudioAttributes

/** One focus request for the session, never one request per silent decoder. Main-thread only. */
@OptIn(UnstableApi::class)
internal class MultiviewAudioFocus private constructor(context: Context) {
    private val context = context.applicationContext
    private val audioManager = AudioManagerCompat.getAudioManager(context)
    private val players = LinkedHashSet<AndroidMultiviewPlayer>()
    private var owner: AndroidMultiviewPlayer? = null
    private var request: AudioFocusRequestCompat? = null
    private var focusGeneration = 0L
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) suspendSession()
        }
    }

    init {
        ContextCompat.registerReceiver(this.context, noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun onFocusChange(generation: Long, change: Int) {
        if (generation != focusGeneration) return
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            suspendSession()
        }
    }

    private fun suspendSession() {
        val active = players.toList()
        // Silence the session before stopping any decoder or relinquishing focus.
        active.forEach { it.silence() }
        owner = null
        val previous = request
        request = null
        focusGeneration++
        previous?.let { AudioManagerCompat.abandonAudioFocusRequest(audioManager, it) }
        active.forEach { it.suspendForAudioFocusLoss() }
    }

    fun register(player: AndroidMultiviewPlayer) { players += player }

    fun unregister(player: AndroidMultiviewPlayer) {
        abandon(player)
        players -= player
        if (players.isEmpty()) {
            context.unregisterReceiver(noisyReceiver)
            instance = null
        }
    }

    fun resumeSession() { players.toList().forEach { it.resumeAfterAudioFocusLoss() } }

    fun acquire(player: AndroidMultiviewPlayer): Boolean {
        if (owner === player) return true
        owner?.setMuted(true)
        val generation = ++focusGeneration
        val nextRequest = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(playbackAudioAttributes)
            .setOnAudioFocusChangeListener({ change -> onFocusChange(generation, change) }, Handler(Looper.getMainLooper()))
            .build()
        request = nextRequest
        if (AudioManagerCompat.requestAudioFocus(audioManager, nextRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            suspendSession()
            return false
        }
        if (generation != focusGeneration) return false
        owner = player
        return true
    }

    fun abandon(player: AndroidMultiviewPlayer) {
        if (owner !== player) return
        owner = null
        val previous = request
        request = null
        focusGeneration++
        previous?.let { AudioManagerCompat.abandonAudioFocusRequest(audioManager, it) }
    }

    companion object {
        private var instance: MultiviewAudioFocus? = null
        fun session(context: Context): MultiviewAudioFocus = instance
            ?: MultiviewAudioFocus(context.applicationContext).also { instance = it }
    }
}
