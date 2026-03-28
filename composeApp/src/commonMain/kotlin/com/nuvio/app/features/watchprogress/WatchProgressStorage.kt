package com.nuvio.app.features.watchprogress

internal expect object WatchProgressStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}
