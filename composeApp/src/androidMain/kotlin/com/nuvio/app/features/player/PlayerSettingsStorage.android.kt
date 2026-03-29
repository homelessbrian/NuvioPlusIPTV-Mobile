package com.nuvio.app.features.player

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

actual object PlayerSettingsStorage {
    private const val preferencesName = "nuvio_player_settings"
    private const val showLoadingOverlayKey = "show_loading_overlay"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadShowLoadingOverlay(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(showLoadingOverlayKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, true)
            } else {
                null
            }
        }

    actual fun saveShowLoadingOverlay(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(showLoadingOverlayKey), enabled)
            ?.apply()
    }
}
