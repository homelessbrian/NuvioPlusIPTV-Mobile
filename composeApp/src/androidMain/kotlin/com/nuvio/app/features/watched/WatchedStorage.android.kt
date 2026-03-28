package com.nuvio.app.features.watched

import android.content.Context
import android.content.SharedPreferences

actual object WatchedStorage {
    private const val preferencesName = "nuvio_watched"
    private const val payloadKey = "watched_payload"

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

