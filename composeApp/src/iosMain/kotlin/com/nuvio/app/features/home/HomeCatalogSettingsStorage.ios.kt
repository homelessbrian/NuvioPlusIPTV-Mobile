package com.nuvio.app.features.home

import platform.Foundation.NSUserDefaults

actual object HomeCatalogSettingsStorage {
    private const val payloadKey = "catalog_settings_payload"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(payloadKey)

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = payloadKey)
    }
}
