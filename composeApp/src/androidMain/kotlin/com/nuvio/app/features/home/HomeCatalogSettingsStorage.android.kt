package com.nuvio.app.features.home

import android.content.Context
import android.content.SharedPreferences

actual object HomeCatalogSettingsStorage {
    private const val preferencesName = "nuvio_home_catalog_settings"
    private const val payloadKey = "catalog_settings_payload"

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
