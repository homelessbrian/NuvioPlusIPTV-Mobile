package com.nuvio.app.features.watched

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class StoredWatchedPayload(
    val items: List<WatchedItem> = emptyList(),
)

object WatchedRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _uiState = MutableStateFlow(WatchedUiState())
    val uiState: StateFlow<WatchedUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var itemsByKey: MutableMap<String, WatchedItem> = mutableMapOf()

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true

        val payload = WatchedStorage.loadPayload().orEmpty().trim()
        if (payload.isNotEmpty()) {
            val items = runCatching {
                json.decodeFromString<StoredWatchedPayload>(payload).items
            }.getOrDefault(emptyList())
            itemsByKey = items.associateBy { watchedItemKey(it.type, it.id) }.toMutableMap()
        }

        publish()
    }

    fun toggleWatched(item: WatchedItem) {
        ensureLoaded()
        val key = watchedItemKey(item.type, item.id)
        if (itemsByKey.containsKey(key)) {
            unmarkWatched(item.id, item.type)
        } else {
            markWatched(item)
        }
    }

    fun markWatched(item: WatchedItem) {
        ensureLoaded()
        val key = watchedItemKey(item.type, item.id)
        itemsByKey[key] = item.copy(markedAtEpochMs = WatchedClock.nowEpochMs())
        publish()
        persist()
    }

    fun unmarkWatched(id: String, type: String) {
        ensureLoaded()
        if (itemsByKey.remove(watchedItemKey(type, id)) != null) {
            publish()
            persist()
        }
    }

    fun isWatched(id: String, type: String): Boolean {
        ensureLoaded()
        return itemsByKey.containsKey(watchedItemKey(type, id))
    }

    private fun publish() {
        val items = itemsByKey.values.sortedByDescending { it.markedAtEpochMs }
        _uiState.value = WatchedUiState(
            items = items,
            watchedKeys = items.mapTo(linkedSetOf()) { watchedItemKey(it.type, it.id) },
            isLoaded = true,
        )
    }

    private fun persist() {
        WatchedStorage.savePayload(
            json.encodeToString(
                StoredWatchedPayload(
                    items = itemsByKey.values.sortedByDescending { it.markedAtEpochMs },
                ),
            ),
        )
    }
}

