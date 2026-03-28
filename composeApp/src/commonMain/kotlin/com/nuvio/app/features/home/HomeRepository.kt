package com.nuvio.app.features.home

import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.catalog.fetchCatalogPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

object HomeRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null
    private var activeRequestKey: String? = null
    private var lastRequestKey: String? = null
    private var currentDefinitions: List<HomeCatalogDefinition> = emptyList()
    private var cachedSections: Map<String, HomeCatalogSection> = emptyMap()
    private var lastErrorMessage: String? = null

    fun refresh(addons: List<ManagedAddon>, force: Boolean = false) {
        val requests = buildHomeCatalogDefinitions(addons)
        currentDefinitions = requests
        val requestKey = requests.joinToString(separator = "|") { request ->
            "${request.manifestUrl}:${request.type}:${request.catalogId}"
        }

        if (!force && activeRequestKey == requestKey && _uiState.value.isLoading) {
            return
        }

        if (!force && requestKey == lastRequestKey && cachedSections.isNotEmpty()) {
            if (_uiState.value.sections.isEmpty() || _uiState.value.heroItems.isEmpty()) {
                applyCurrentSettings()
            }
            return
        }
        lastRequestKey = requestKey
        activeRequestKey = requestKey

        if (requests.isEmpty()) {
            activeJob?.cancel()
            activeJob = null
            activeRequestKey = null
            cachedSections = emptyMap()
            lastErrorMessage = null
            _uiState.value = HomeUiState(
                isLoading = false,
                sections = emptyList(),
                errorMessage = null,
            )
            return
        }

        activeJob?.cancel()
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        activeJob = scope.launch {
            val results = mutableListOf<Result<HomeCatalogSection>>()
            requests.chunked(HOME_CATALOG_FETCH_BATCH_SIZE).forEach { batch ->
                if (activeRequestKey != requestKey) return@launch
                results += batch.map { request ->
                    async {
                        runCatching { request.toSection() }
                    }
                }.awaitAll()
            }

            if (activeRequestKey != requestKey) return@launch

            cachedSections = results
                .mapNotNull { it.getOrNull() }
                .associateBy { it.key }
            lastErrorMessage = results.firstNotNullOfOrNull { it.exceptionOrNull()?.message }
            applyCurrentSettings()
        }
    }

    fun applyCurrentSettings() {
        val snapshot = HomeCatalogSettingsRepository.snapshot()
        val preferences = snapshot.preferences
        val sections = currentDefinitions
            .sortedBy { definition -> preferences[definition.key]?.order ?: Int.MAX_VALUE }
            .mapNotNull { definition ->
                val preference = preferences[definition.key]
                if (preference?.enabled == false) return@mapNotNull null

                val section = cachedSections[definition.key] ?: return@mapNotNull null
                val customTitle = preference?.customTitle.orEmpty()
                section.copy(
                    title = customTitle.ifBlank { section.title },
                )
            }

        val heroItems = if (snapshot.heroEnabled) {
            currentDefinitions
                .filter { definition -> preferences[definition.key]?.heroSourceEnabled != false }
                .mapNotNull { definition -> cachedSections[definition.key] }
                .flatMap { section -> section.items }
                .distinctBy { item -> "${item.type}:${item.id}" }
                .shuffled(Random.Default)
                .take(HOME_HERO_ITEM_LIMIT)
        } else {
            emptyList()
        }

        _uiState.value = HomeUiState(
            isLoading = false,
            heroItems = heroItems,
            sections = sections,
            errorMessage = if (sections.isEmpty()) lastErrorMessage else null,
        )
    }

    private suspend fun HomeCatalogDefinition.toSection(): HomeCatalogSection {
        val page = fetchCatalogPage(
            manifestUrl = manifestUrl,
            type = type,
            catalogId = catalogId,
        )
        val items = page.items
        require(items.isNotEmpty()) { "No feed items returned for $defaultTitle." }

        return HomeCatalogSection(
            key = key,
            title = defaultTitle,
            subtitle = addonName,
            addonName = addonName,
            type = type,
            manifestUrl = manifestUrl,
            catalogId = catalogId,
            items = items,
            supportsPagination = supportsPagination,
        )
    }
}

private const val HOME_HERO_ITEM_LIMIT = 8
private const val HOME_CATALOG_FETCH_BATCH_SIZE = 4
