package com.nuvio.app.features.watchprogress

import platform.Foundation.NSUserDefaults

actual object ContinueWatchingPreferencesStorage {
    private const val payloadKey = "continue_watching_preferences_payload"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(payloadKey)

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = payloadKey)
    }
}
