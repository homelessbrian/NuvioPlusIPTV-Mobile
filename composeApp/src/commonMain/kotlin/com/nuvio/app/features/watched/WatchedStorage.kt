package com.nuvio.app.features.watched

expect object WatchedStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}

