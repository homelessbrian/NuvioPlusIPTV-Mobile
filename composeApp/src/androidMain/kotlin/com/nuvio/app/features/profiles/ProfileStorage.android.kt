package com.nuvio.app.features.profiles

import android.content.Context
import android.content.SharedPreferences

actual object ProfileStorage {
    private const val preferencesName = "nuvio_profile_cache"
    private const val payloadKey = "profile_payload"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        if (loadPayload().isNullOrBlank() || ProfileRepository.state.value.isLoaded) {
            onActiveProfileChanged(ProfileRepository.activeProfileId)
        }
    }

    /** Read-only bootstrap for alarm/service networking, without loading profile UI or caches. */
    internal fun savedActiveProfileForBackground(context: Context): Int {
        val stored = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).getString(payloadKey, null)
            ?.takeIf { it.isNotBlank() } ?: return 1
        try {
            val payload = org.json.JSONObject(stored)
            val requested = payload.optInt("activeProfileIndex", 1)
            val profiles = payload.optJSONArray("profiles")
            if (profiles != null && profiles.length() > 0) {
                val ids = (0 until profiles.length()).mapNotNull { profiles.optJSONObject(it)?.optInt("profileIndex")?.takeIf { id -> id > 0 } }
                return requested.takeIf { it in ids } ?: ids.firstOrNull()
                    ?: throw IllegalStateException("The saved active profile is unavailable.")
            }
            return requested.takeIf { it > 0 } ?: throw IllegalStateException("The saved active profile is unavailable.")
        } catch (_: Exception) { throw IllegalStateException("The saved active profile is unavailable.") }
    }

    actual fun onActiveProfileChanged(id: Int) {
        if (preferences != null) com.nuvio.vpn.android.VpnPlaybackProtection.setActiveUserProfile(id)
    }

    actual fun onProfileRemoved(id: Int) {
        if (preferences != null) com.nuvio.vpn.android.VpnPlaybackProtection.removeUserProfile(id)
    }

    actual fun loadPayload(): String? =
        preferences?.getString(payloadKey, null)

    actual fun savePayload(payload: String) {
        preferences
            ?.edit()
            ?.putString(payloadKey, payload)
            ?.apply()
    }
}
