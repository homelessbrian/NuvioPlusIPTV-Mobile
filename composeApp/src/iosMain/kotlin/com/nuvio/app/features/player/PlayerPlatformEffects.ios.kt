package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.UIKit.UIApplication

@Composable
actual fun LockPlayerToLandscape() {
    // iOS handles orientation through Info.plist supported orientations
    // The player view will fill available space in landscape
}

@Composable
actual fun EnterImmersivePlayerMode() {
    DisposableEffect(Unit) {
        // Request idle timer disabled to keep screen awake during playback
        UIApplication.sharedApplication.setIdleTimerDisabled(true)
        onDispose {
            UIApplication.sharedApplication.setIdleTimerDisabled(false)
        }
    }
}
