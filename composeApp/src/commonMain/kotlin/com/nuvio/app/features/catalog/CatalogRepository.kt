package com.nuvio.app.features.catalog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object CatalogRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null
    private var activeRequest: CatalogRequest? = null

    fun load(
        manifestUrl: String,
        type: String,
        catalogId: String,
        genre: String? = null,
        supportsPagination: Boolean = false,
        force: Boolean = false,
    ) {
        val request = CatalogRequest(
            manifestUrl = manifestUrl,
            type = type,
            catalogId = catalogId,
            genre = genre,
            supportsPagination = supportsPagination,
        )
        if (!force && activeRequest == request && (_uiState.value.items.isNotEmpty() || _uiState.value.isLoading)) {
            return
        }
        activeRequest = request
        fetchPage(request = request, reset = true)
    }

    fun loadMore() {
        val request = activeRequest ?: return
        val current = _uiState.value
        if (current.isLoading || current.nextSkip == null) return
        fetchPage(request = request, reset = false)
    }

    fun clear() {
        activeJob?.cancel()
        activeRequest = null
        _uiState.value = CatalogUiState()
    }

    private fun fetchPage(
        request: CatalogRequest,
        reset: Boolean,
    ) {
        activeJob?.cancel()
        val current = _uiState.value
        val requestedSkip = if (reset) 0 else current.nextSkip ?: return

        _uiState.value = current.copy(
            items = if (reset) emptyList() else current.items,
            isLoading = true,
            nextSkip = if (reset) null else current.nextSkip,
            errorMessage = null,
        )

        activeJob = scope.launch {
            runCatching {
                fetchCatalogPage(
                    manifestUrl = request.manifestUrl,
                    type = request.type,
                    catalogId = request.catalogId,
                    genre = request.genre,
                    skip = requestedSkip.takeIf { it > 0 },
                )
            }.fold(
                onSuccess = { page ->
                    if (activeRequest != request) return@fold

                    val mergedItems = if (reset) {
                        page.items
                    } else {
                        mergeCatalogItems(_uiState.value.items, page.items)
                    }
                    _uiState.value = CatalogUiState(
                        items = mergedItems,
                        isLoading = false,
                        nextSkip = if (request.supportsPagination) page.nextSkip else null,
                        errorMessage = null,
                    )
                },
                onFailure = { error ->
                    if (activeRequest != request) return@fold

                    _uiState.value = current.copy(
                        items = if (reset) emptyList() else current.items,
                        isLoading = false,
                        nextSkip = null,
                        errorMessage = error.message ?: "Unable to load catalog items.",
                    )
                },
            )
        }
    }
}

private data class CatalogRequest(
    val manifestUrl: String,
    val type: String,
    val catalogId: String,
    val genre: String?,
    val supportsPagination: Boolean,
)
