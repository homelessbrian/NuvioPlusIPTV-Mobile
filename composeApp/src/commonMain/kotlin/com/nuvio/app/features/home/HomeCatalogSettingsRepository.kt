package com.nuvio.app.features.home

import com.nuvio.app.features.addons.ManagedAddon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class HomeCatalogSettingsItem(
    val key: String,
    val defaultTitle: String,
    val addonName: String,
    val customTitle: String = "",
    val enabled: Boolean = true,
    val order: Int = 0,
) {
    val displayTitle: String
        get() = customTitle.ifBlank { defaultTitle }
}

data class HomeCatalogSettingsUiState(
    val items: List<HomeCatalogSettingsItem> = emptyList(),
) {
    val signature: String
        get() = items.joinToString(separator = "|") { item ->
            "${item.key}:${item.order}:${item.enabled}:${item.customTitle}"
        }
}

internal data class HomeCatalogPreference(
    val customTitle: String,
    val enabled: Boolean,
    val order: Int,
)

@Serializable
private data class StoredHomeCatalogPreference(
    val key: String,
    val customTitle: String = "",
    val enabled: Boolean = true,
    val order: Int = 0,
)

object HomeCatalogSettingsRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _uiState = MutableStateFlow(HomeCatalogSettingsUiState())
    val uiState: StateFlow<HomeCatalogSettingsUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var definitions: List<HomeCatalogDefinition> = emptyList()
    private var preferences: MutableMap<String, StoredHomeCatalogPreference> = mutableMapOf()

    fun syncCatalogs(addons: List<ManagedAddon>) {
        ensureLoaded()
        definitions = buildHomeCatalogDefinitions(addons)
        normalizePreferences()
        publish()
        persist()
    }

    internal fun snapshot(): Map<String, HomeCatalogPreference> {
        ensureLoaded()
        return preferences.mapValues { (_, value) ->
            HomeCatalogPreference(
                customTitle = value.customTitle,
                enabled = value.enabled,
                order = value.order,
            )
        }
    }

    fun setEnabled(key: String, enabled: Boolean) {
        updatePreference(key) { preference ->
            preference.copy(enabled = enabled)
        }
    }

    fun setCustomTitle(key: String, title: String) {
        updatePreference(key) { preference ->
            preference.copy(customTitle = title)
        }
    }

    fun moveUp(key: String) {
        move(key = key, direction = -1)
    }

    fun moveDown(key: String) {
        move(key = key, direction = 1)
    }

    private fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true

        val payload = HomeCatalogSettingsStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) return

        val stored = runCatching {
            json.decodeFromString<List<StoredHomeCatalogPreference>>(payload)
        }.getOrDefault(emptyList())

        preferences = stored.associateBy { it.key }.toMutableMap()
    }

    private fun normalizePreferences() {
        val current = preferences
        val orderedDefinitions = definitions.mapIndexed { defaultIndex, definition ->
            Triple(
                definition,
                current[definition.key]?.order ?: defaultIndex,
                defaultIndex,
            )
        }.sortedWith(
            compareBy<Triple<HomeCatalogDefinition, Int, Int>>(
                { it.second },
                { it.third },
            ),
        ).map { it.first }

        val normalized = mutableMapOf<String, StoredHomeCatalogPreference>()
        orderedDefinitions.forEachIndexed { index, definition ->
            val stored = current[definition.key]
            normalized[definition.key] = StoredHomeCatalogPreference(
                key = definition.key,
                customTitle = stored?.customTitle.orEmpty(),
                enabled = stored?.enabled ?: true,
                order = index,
            )
        }
        preferences = normalized
    }

    private fun publish() {
        val items = definitions
            .sortedBy { definition -> preferences[definition.key]?.order ?: Int.MAX_VALUE }
            .map { definition ->
                val preference = preferences[definition.key]
                HomeCatalogSettingsItem(
                    key = definition.key,
                    defaultTitle = definition.defaultTitle,
                    addonName = definition.addonName,
                    customTitle = preference?.customTitle.orEmpty(),
                    enabled = preference?.enabled ?: true,
                    order = preference?.order ?: 0,
                )
            }

        _uiState.value = HomeCatalogSettingsUiState(items = items)
    }

    private fun persist() {
        HomeCatalogSettingsStorage.savePayload(
            json.encodeToString(
                preferences.values.sortedBy { it.order },
            ),
        )
    }

    private fun updatePreference(
        key: String,
        transform: (StoredHomeCatalogPreference) -> StoredHomeCatalogPreference,
    ) {
        ensureLoaded()
        val current = preferences[key] ?: return
        preferences[key] = transform(current)
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    private fun move(
        key: String,
        direction: Int,
    ) {
        ensureLoaded()
        if (definitions.isEmpty()) return

        val orderedKeys = definitions
            .sortedBy { definition -> preferences[definition.key]?.order ?: Int.MAX_VALUE }
            .map { it.key }
            .toMutableList()

        val currentIndex = orderedKeys.indexOf(key)
        if (currentIndex == -1) return

        val targetIndex = currentIndex + direction
        if (targetIndex !in orderedKeys.indices) return

        val movingKey = orderedKeys.removeAt(currentIndex)
        orderedKeys.add(targetIndex, movingKey)

        orderedKeys.forEachIndexed { index, itemKey ->
            val current = preferences[itemKey] ?: return@forEachIndexed
            preferences[itemKey] = current.copy(order = index)
        }

        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }
}
