package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplication

private const val lockPlayerToLandscapeNotification = "NuvioPlayerLockLandscape"
private const val unlockPlayerOrientationNotification = "NuvioPlayerUnlockOrientation"

@Composable
actual fun LockPlayerToLandscape() {
    DisposableEffect(Unit) {
        NSNotificationCenter.defaultCenter.postNotificationName(
            lockPlayerToLandscapeNotification,
            null,
        )

        onDispose {
            NSNotificationCenter.defaultCenter.postNotificationName(
                unlockPlayerOrientationNotification,
                null,
            )
        }
    }
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
