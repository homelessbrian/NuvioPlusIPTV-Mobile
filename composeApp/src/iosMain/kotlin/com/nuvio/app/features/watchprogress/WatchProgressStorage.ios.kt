package com.nuvio.app.features.watchprogress

import platform.Foundation.NSUserDefaults

actual object WatchProgressStorage {
    private const val payloadKey = "watch_progress_payload"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(payloadKey)

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = payloadKey)
    }
}
