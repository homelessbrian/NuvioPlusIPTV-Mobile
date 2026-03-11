package com.nuvio.app.features.home

import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.catalog.fetchCatalogPage
import com.nuvio.app.features.catalog.supportsPagination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object HomeRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var lastRequestKey: String? = null

    fun refresh(addons: List<ManagedAddon>, force: Boolean = false) {
        val requests = buildCatalogRequests(addons)
        val requestKey = requests.joinToString(separator = "|") { request ->
            "${request.addon.manifestUrl}:${request.type}:${request.catalogId}"
        }

        if (!force && requestKey == lastRequestKey) return
        lastRequestKey = requestKey

        if (requests.isEmpty()) {
            _uiState.value = HomeUiState(
                isLoading = false,
                sections = emptyList(),
                errorMessage = null,
            )
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        scope.launch {
            val results = requests.map { request ->
                async {
                    runCatching { request.toSection() }
                }
            }.awaitAll()

            val sections = results.mapNotNull { it.getOrNull() }
            val firstFailure = results.firstNotNullOfOrNull { it.exceptionOrNull()?.message }

            _uiState.value = HomeUiState(
                isLoading = false,
                sections = sections,
                errorMessage = if (sections.isEmpty()) firstFailure else null,
            )
        }
    }

    private fun buildCatalogRequests(addons: List<ManagedAddon>): List<CatalogRequest> =
        addons.mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            addon to manifest
        }.flatMap { (addon, manifest) ->
            manifest.catalogs
                .filter { catalog -> catalog.extra.none { it.isRequired } }
                .map { catalog ->
                    CatalogRequest(
                        addon = addon,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        type = catalog.type,
                        supportsPagination = catalog.supportsPagination(),
                    )
                }
        }

    private suspend fun CatalogRequest.toSection(): HomeCatalogSection {
        val manifest = requireNotNull(addon.manifest)
        val page = fetchCatalogPage(
            manifestUrl = manifest.transportUrl,
            type = type,
            catalogId = catalogId,
        )
        val items = page.items
        require(items.isNotEmpty()) { "No feed items returned for $catalogName." }

        return HomeCatalogSection(
            key = "${manifest.id}:$type:$catalogId",
            title = "$catalogName - ${type.displayLabel()}",
            subtitle = manifest.name,
            addonName = manifest.name,
            type = type,
            manifestUrl = manifest.transportUrl,
            catalogId = catalogId,
            items = items,
            supportsPagination = supportsPagination,
        )
    }
}

private fun String.displayLabel(): String =
    replaceFirstChar { char ->
        if (char.isLowerCase()) char.titlecase() else char.toString()
    }
