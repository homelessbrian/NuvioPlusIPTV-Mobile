package com.nuvio.app.features.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class StoredLibraryPayload(
    val items: List<LibraryItem> = emptyList(),
)

object LibraryRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var itemsById: MutableMap<String, LibraryItem> = mutableMapOf()

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true

        val payload = LibraryStorage.loadPayload().orEmpty().trim()
        if (payload.isNotEmpty()) {
            val items = runCatching {
                json.decodeFromString<StoredLibraryPayload>(payload).items
            }.getOrDefault(emptyList())
            itemsById = items.associateBy { it.id }.toMutableMap()
        }

        publish()
    }

    fun toggleSaved(item: LibraryItem) {
        ensureLoaded()
        if (itemsById.containsKey(item.id)) {
            remove(item.id)
        } else {
            save(item)
        }
    }

    fun save(item: LibraryItem) {
        ensureLoaded()
        itemsById[item.id] = item.copy(savedAtEpochMs = LibraryClock.nowEpochMs())
        publish()
        persist()
    }

    fun remove(id: String) {
        ensureLoaded()
        if (itemsById.remove(id) != null) {
            publish()
            persist()
        }
    }

    fun isSaved(id: String): Boolean {
        ensureLoaded()
        return itemsById.containsKey(id)
    }

    fun savedItem(id: String): LibraryItem? {
        ensureLoaded()
        return itemsById[id]
    }

    private fun publish() {
        val items = itemsById.values
            .sortedByDescending { it.savedAtEpochMs }
        val sections = items
            .groupBy { it.type }
            .map { (type, typeItems) ->
                LibrarySection(
                    type = type,
                    displayTitle = type.toLibraryDisplayTitle(),
                    items = typeItems.sortedByDescending { it.savedAtEpochMs },
                )
            }
            .sortedBy { it.displayTitle }

        _uiState.value = LibraryUiState(
            items = items,
            sections = sections,
            isLoaded = true,
        )
    }

    private fun persist() {
        LibraryStorage.savePayload(
            json.encodeToString(
                StoredLibraryPayload(
                    items = itemsById.values.sortedByDescending { it.savedAtEpochMs },
                ),
            ),
        )
    }
}

internal fun String.toLibraryDisplayTitle(): String {
    val normalized = trim()
    if (normalized.isBlank()) return "Other"

    return normalized
        .split('-', '_', ' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token.lowercase().replaceFirstChar { char -> char.uppercase() }
        }
        .ifBlank { "Other" }
}
