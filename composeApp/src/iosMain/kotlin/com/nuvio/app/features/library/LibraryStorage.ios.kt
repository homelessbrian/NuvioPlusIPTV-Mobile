package com.nuvio.app.features.library

import platform.Foundation.NSUserDefaults

actual object LibraryStorage {
    private const val payloadKey = "library_payload"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(payloadKey)

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = payloadKey)
    }
}
