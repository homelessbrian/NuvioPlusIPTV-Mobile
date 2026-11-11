package com.nuvio.app.features.livetv.multiview

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.nuvio.app.features.livetv.model.LiveChannel
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout

actual val isMultiviewSupported: Boolean = true

@Composable
actual fun rememberMultiviewPlayer(mayPlayChannel: ((LiveChannel) -> Boolean)?): MultiviewPlayer {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentGuard = rememberUpdatedState(mayPlayChannel)
    val player = remember(context) {
        val fallback = multiviewChannelPermissionGuard()
        AndroidMultiviewPlayer(context) { channel -> currentGuard.value?.invoke(channel) ?: fallback(channel) }
    }
    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> player.setLifecycleStarted(true)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> player.setLifecycleStarted(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        player.setLifecycleStarted(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.setLifecycleStarted(false)
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

@Composable
actual fun PlatformMultiviewSurface(player: MultiviewPlayer, modifier: Modifier) {
    val androidPlayer = player as? AndroidMultiviewPlayer ?: return
    AndroidView(
        modifier = modifier,
        factory = { context -> MultiviewVideoView(context).also(androidPlayer::attachSurface) },
        update = { androidPlayer.attachSurface(it) },
        onRelease = { androidPlayer.detachSurface(it) },
    )
}

/** A texture stays in the normal view hierarchy, beneath Compose pane borders and controls. */
@OptIn(UnstableApi::class)
internal class MultiviewVideoView(context: Context) : FrameLayout(context) {
    private val aspectFrame = AspectRatioFrameLayout(context).apply {
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
    private val texture = TextureView(context).apply {
        isOpaque = true
        isFocusable = false
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var player: ExoPlayer? = null
    private var secureSurface: SurfaceView? = null
    private val shutter = View(context).apply { setBackgroundColor(Color.BLACK) }
    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) { updateAspect(videoSize) }
        override fun onRenderedFirstFrame() { shutter.visibility = View.GONE }
    }

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = false
        isClickable = false
        clipChildren = true
        clipToPadding = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(aspectFrame, LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
        aspectFrame.addView(texture, LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
        addView(shutter, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    fun bind(next: ExoPlayer?, secure: Boolean = false) {
        if (player === next) return
        player?.run {
            removeListener(listener)
            secureSurface?.let(::clearVideoSurfaceView) ?: clearVideoTextureView(texture)
        }
        secureSurface?.let(aspectFrame::removeView)
        secureSurface = null
        player = next
        shutter.visibility = View.VISIBLE
        if (next == null) {
            texture.visibility = View.INVISIBLE
            aspectFrame.setAspectRatio(0f)
            keepScreenOn = false
        } else {
            next.addListener(listener)
            updateAspect(next.videoSize)
            if (secure) {
                // Secure decoders require a secure SurfaceView. Its normal window z-order keeps
                // Compose pane borders, channel labels and touch controls above the video.
                texture.visibility = View.GONE
                val surfaceView = SurfaceView(context).apply {
                    setSecure(true)
                    setZOrderOnTop(false)
                    isFocusable = false
                    isClickable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                secureSurface = surfaceView
                aspectFrame.addView(surfaceView, LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
                next.setVideoSurfaceView(surfaceView)
            } else {
                texture.visibility = View.VISIBLE
                next.setVideoTextureView(texture)
            }
            keepScreenOn = true
        }
    }

    private fun updateAspect(size: VideoSize) {
        val ratio = if (size.width > 0 && size.height > 0) {
            size.width * size.pixelWidthHeightRatio / size.height
        } else 0f
        aspectFrame.setAspectRatio(ratio)
    }
}
