package com.nuvio.app.features.watchprogress

import android.content.Context
import android.content.SharedPreferences

actual object ContinueWatchingPreferencesStorage {
    private const val preferencesName = "nuvio_continue_watching_preferences"
    private const val payloadKey = "continue_watching_preferences_payload"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
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
