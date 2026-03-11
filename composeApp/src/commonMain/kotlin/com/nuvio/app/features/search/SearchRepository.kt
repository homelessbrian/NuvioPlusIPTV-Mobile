package com.nuvio.app.features.search

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.home.HomeCatalogParser
import com.nuvio.app.features.home.HomeCatalogSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object SearchRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null
    private var lastRequestKey: String? = null

    fun search(query: String, addons: List<ManagedAddon>) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            clear()
            return
        }

        val activeAddons = addons.filter { it.manifest != null }
        if (activeAddons.isEmpty()) {
            activeJob?.cancel()
            lastRequestKey = null
            _uiState.value = SearchUiState(
                emptyStateReason = SearchEmptyStateReason.NoActiveAddons,
            )
            return
        }

        val requests = buildSearchRequests(
            addons = activeAddons,
            query = normalizedQuery,
        )
        if (requests.isEmpty()) {
            activeJob?.cancel()
            lastRequestKey = null
            _uiState.value = SearchUiState(
                emptyStateReason = SearchEmptyStateReason.NoSearchCatalogs,
            )
            return
        }

        val requestKey = buildString {
            append(normalizedQuery.lowercase())
            append('|')
            append(
                requests.joinToString(separator = "|") { request ->
                    "${request.addon.manifestUrl}:${request.type}:${request.catalogId}"
                },
            )
        }
        if (requestKey == lastRequestKey) return
        lastRequestKey = requestKey

        activeJob?.cancel()
        _uiState.value = SearchUiState(isLoading = true)

        activeJob = scope.launch {
            val results = requests.map { request ->
                async {
                    runCatching { request.toSection() }
                }
            }.awaitAll()

            val sections = results
                .mapNotNull { it.getOrNull() }
                .sortedBy { it.title.lowercase() }
            val firstFailure = results.firstNotNullOfOrNull { it.exceptionOrNull()?.message }
            val allFailed = results.isNotEmpty() && results.all { it.isFailure }

            _uiState.value = SearchUiState(
                isLoading = false,
                sections = sections,
                emptyStateReason = when {
                    sections.isNotEmpty() -> null
                    allFailed -> SearchEmptyStateReason.RequestFailed
                    else -> SearchEmptyStateReason.NoResults
                },
                errorMessage = if (allFailed) firstFailure else null,
            )
        }
    }

    fun clear() {
        activeJob?.cancel()
        lastRequestKey = null
        _uiState.value = SearchUiState()
    }

    private fun buildSearchRequests(
        addons: List<ManagedAddon>,
        query: String,
    ): List<SearchCatalogRequest> =
        addons.mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            addon to manifest
        }.flatMap { (addon, manifest) ->
            manifest.catalogs
                .filter { catalog -> catalog.supportsSearch() }
                .map { catalog ->
                    SearchCatalogRequest(
                        addon = addon,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        type = catalog.type,
                        query = query,
                    )
                }
        }

    private suspend fun SearchCatalogRequest.toSection(): HomeCatalogSection {
        val manifest = requireNotNull(addon.manifest)
        val catalogUrl = buildSearchCatalogUrl(
            manifestUrl = manifest.transportUrl,
            type = type,
            catalogId = catalogId,
            query = query,
        )
        val payload = httpGetText(catalogUrl)
        val items = HomeCatalogParser.parseCatalog(payload)
        require(items.isNotEmpty()) { "No search results returned for $catalogName." }

        return HomeCatalogSection(
            key = "${manifest.id}:search:$type:$catalogId:${query.lowercase()}",
            title = "$catalogName - ${type.displayLabel()}",
            subtitle = manifest.name,
            addonName = manifest.name,
            type = type,
            manifestUrl = manifest.transportUrl,
            catalogId = catalogId,
            items = items,
        )
    }
}

private data class SearchCatalogRequest(
    val addon: ManagedAddon,
    val catalogId: String,
    val catalogName: String,
    val type: String,
    val query: String,
)

private fun AddonCatalog.supportsSearch(): Boolean =
    extra.any { property -> property.name == "search" } &&
        extra.none { property -> property.isRequired && property.name != "search" }

private fun buildSearchCatalogUrl(
    manifestUrl: String,
    type: String,
    catalogId: String,
    query: String,
): String {
    val baseUrl = manifestUrl
        .substringBefore("?")
        .removeSuffix("/manifest.json")
    return "$baseUrl/catalog/$type/$catalogId/search=${query.encodeForSearchExtra()}.json"
}

private fun String.displayLabel(): String =
    replaceFirstChar { char ->
        if (char.isLowerCase()) char.titlecase() else char.toString()
    }

private fun String.encodeForSearchExtra(): String =
    buildString {
        encodeToByteArray().forEach { byte ->
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (
                char in 'a'..'z' ||
                char in 'A'..'Z' ||
                char in '0'..'9' ||
                char == '-' ||
                char == '_' ||
                char == '.' ||
                char == '~'
            ) {
                append(char)
            } else {
                append('%')
                append(HEX[value shr 4])
                append(HEX[value and 0x0F])
            }
        }
    }

private val HEX = "0123456789ABCDEF"
