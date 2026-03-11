package com.nuvio.app.features.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.home.components.HomeSkeletonRow
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
) {
    LaunchedEffect(Unit) {
        AddonRepository.initialize()
    }

    val addonsUiState by AddonRepository.uiState.collectAsStateWithLifecycle()
    val uiState by SearchRepository.uiState.collectAsStateWithLifecycle()
    var query by rememberSaveable { androidx.compose.runtime.mutableStateOf("") }

    val addonRefreshKey = remember(addonsUiState.addons) {
        addonsUiState.addons.mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            buildString {
                append(manifest.transportUrl)
                append(':')
                append(manifest.catalogs.joinToString(separator = ",") { catalog ->
                    val extra = catalog.extra.joinToString(separator = "&") { property ->
                        "${property.name}:${property.isRequired}"
                    }
                    "${catalog.type}:${catalog.id}:$extra"
                })
            }
        }
    }

    LaunchedEffect(query, addonRefreshKey) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            SearchRepository.clear()
        } else {
            delay(350)
            SearchRepository.search(
                query = normalizedQuery,
                addons = addonsUiState.addons,
            )
        }
    }

    NuvioScreen(
        modifier = modifier,
        horizontalPadding = 0.dp,
    ) {
        stickyHeader {
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
            ) {
                NuvioScreenHeader(title = "Search")
                Spacer(modifier = Modifier.height(12.dp))
                NuvioInputField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search installed addon catalogs",
                )
            }
        }

        when {
            query.isBlank() -> Unit

            uiState.isLoading && uiState.sections.isEmpty() -> {
                items(2) {
                    HomeSkeletonRow(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }

            uiState.sections.isEmpty() -> {
                item {
                    SearchEmptyStateCard(
                        reason = uiState.emptyStateReason,
                        errorMessage = uiState.errorMessage,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            else -> {
                items(
                    count = uiState.sections.size,
                    key = { index -> uiState.sections[index].key },
                ) { index ->
                    HomeCatalogRowSection(
                        section = uiState.sections[index],
                        modifier = Modifier.padding(bottom = 12.dp),
                        onPosterClick = onPosterClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchEmptyStateCard(
    reason: SearchEmptyStateReason?,
    errorMessage: String?,
    modifier: Modifier = Modifier,
) {
    val title: String
    val message: String

    when (reason) {
        SearchEmptyStateReason.NoActiveAddons -> {
            title = "No active addons"
            message = "Install and validate at least one addon before searching."
        }

        SearchEmptyStateReason.NoSearchCatalogs -> {
            title = "No searchable catalogs"
            message = "Your installed addons do not expose catalog search."
        }

        SearchEmptyStateReason.RequestFailed -> {
            title = "Search failed"
            message = errorMessage ?: "Installed addons failed to return valid search results."
        }

        SearchEmptyStateReason.NoResults, null -> {
            title = "No results found"
            message = "Installed searchable catalogs did not return any matches for this query."
        }
    }

    HomeEmptyStateCard(
        modifier = modifier,
        title = title,
        message = message,
    )
}
