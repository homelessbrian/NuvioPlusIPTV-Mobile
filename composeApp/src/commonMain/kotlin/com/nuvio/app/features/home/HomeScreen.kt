package com.nuvio.app.features.home

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.home.components.HomeHeroSection
import com.nuvio.app.features.home.components.HomeSkeletonRow

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onCatalogClick: ((HomeCatalogSection) -> Unit)? = null,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
) {
    LaunchedEffect(Unit) {
        AddonRepository.initialize()
    }

    val addonsUiState by AddonRepository.uiState.collectAsStateWithLifecycle()
    val homeUiState by HomeRepository.uiState.collectAsStateWithLifecycle()

    val catalogRefreshKey = remember(addonsUiState.addons) {
        addonsUiState.addons.mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            buildString {
                append(manifest.transportUrl)
                append(':')
                append(manifest.catalogs.joinToString(separator = ",") { catalog ->
                    "${catalog.type}:${catalog.id}:${catalog.extra.count { it.isRequired }}"
                })
            }
        }
    }

    LaunchedEffect(catalogRefreshKey) {
        HomeCatalogSettingsRepository.syncCatalogs(addonsUiState.addons)
        HomeRepository.refresh(addonsUiState.addons)
    }

    NuvioScreen(
        modifier = modifier,
        horizontalPadding = 0.dp,
        topPadding = if (homeUiState.heroItems.isNotEmpty()) 0.dp else null,
    ) {
        when {
            addonsUiState.addons.none { it.manifest != null } -> {
                item {
                    HomeEmptyStateCard(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = "No active addons",
                        message = "Install and validate at least one addon before loading catalog rows on Home.",
                    )
                }
            }

            homeUiState.isLoading && homeUiState.sections.isEmpty() -> {
                items(3) {
                    HomeSkeletonRow(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }

            homeUiState.sections.isEmpty() && homeUiState.heroItems.isEmpty() -> {
                item {
                    HomeEmptyStateCard(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = "No home rows available",
                        message = homeUiState.errorMessage
                            ?: "Installed addons do not currently expose board-compatible catalogs without required extras.",
                    )
                }
            }

            else -> {
                if (homeUiState.heroItems.isNotEmpty()) {
                    item {
                        HomeHeroSection(
                            items = homeUiState.heroItems,
                            modifier = Modifier.padding(bottom = 0.dp),
                            onItemClick = onPosterClick,
                        )
                    }
                }
                items(
                    count = homeUiState.sections.size,
                    key = { index -> homeUiState.sections[index].key },
                ) { index ->
                    val section = homeUiState.sections[index]
                    HomeCatalogRowSection(
                        section = section,
                        entries = section.items.take(HOME_CATALOG_PREVIEW_LIMIT),
                        modifier = Modifier.padding(bottom = 12.dp),
                        onViewAllClick = if (section.canOpenCatalog(HOME_CATALOG_PREVIEW_LIMIT)) {
                            onCatalogClick?.let { { it(section) } }
                        } else {
                            null
                        },
                        onPosterClick = onPosterClick,
                    )
                }
            }
        }
    }
}

private const val HOME_CATALOG_PREVIEW_LIMIT = 18
