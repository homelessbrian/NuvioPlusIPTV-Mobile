package com.nuvio.app.features.library

internal expect object LibraryStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}
