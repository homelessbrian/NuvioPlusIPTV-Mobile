package com.nuvio.app.features.watched

import platform.Foundation.NSUserDefaults

actual object WatchedStorage {
    private const val payloadKey = "watched_payload"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(payloadKey)

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = payloadKey)
    }
}

