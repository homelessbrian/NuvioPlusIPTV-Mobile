package com.nuvio.app.features.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.nuvioPlatformExtraBottomPadding
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.home.components.HomeSkeletonRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

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
    val discoverUiState by SearchRepository.discoverUiState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var headerHeightPx by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    val addonRefreshKey = remember(addonsUiState.addons) {
        addonsUiState.addons.mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            buildString {
                append(manifest.transportUrl)
                append(':')
                append(manifest.catalogs.joinToString(separator = ",") { catalog ->
                    val extra = catalog.extra.joinToString(separator = "&") { property ->
                        buildString {
                            append(property.name)
                            append(':')
                            append(property.isRequired)
                            append(':')
                            append(property.options.joinToString(separator = "|"))
                        }
                    }
                    "${catalog.type}:${catalog.id}:$extra"
                })
            }
        }
    }

    LaunchedEffect(addonRefreshKey) {
        SearchRepository.refreshDiscover(addonsUiState.addons)
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

    LaunchedEffect(listState, query, discoverUiState.canLoadMore, discoverUiState.isLoading) {
        if (query.isNotBlank()) return@LaunchedEffect

        snapshotFlow { listState.layoutInfo }
            .map { layoutInfo ->
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                lastVisible >= layoutInfo.totalItemsCount - 4
            }
            .distinctUntilChanged()
            .filter { it && discoverUiState.canLoadMore && !discoverUiState.isLoading }
            .collect {
                SearchRepository.loadMoreDiscover()
            }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 18.dp + nuvioPlatformExtraBottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(modifier = Modifier.height(with(androidx.compose.ui.platform.LocalDensity.current) { headerHeightPx.toDp() }))
            }

            if (query.isBlank()) {
                item {
                    DiscoverSectionHeader(modifier = Modifier.padding(horizontal = 16.dp))
                }
                item {
                    DiscoverFilterRow(
                        state = discoverUiState,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        onTypeSelected = SearchRepository::selectDiscoverType,
                        onCatalogSelected = SearchRepository::selectDiscoverCatalog,
                        onGenreSelected = SearchRepository::selectDiscoverGenre,
                    )
                }
                discoverUiState.selectedCatalog?.let { selectedCatalog ->
                    item {
                        Text(
                            text = "${selectedCatalog.addonName} • ${selectedCatalog.type.displayTypeLabel()}",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                when {
                    discoverUiState.isLoading && discoverUiState.items.isEmpty() -> {
                        items(2) {
                            DiscoverSkeletonRow(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }

                    discoverUiState.items.isEmpty() -> {
                        item {
                            DiscoverEmptyStateCard(
                                reason = discoverUiState.emptyStateReason,
                                errorMessage = discoverUiState.errorMessage,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }

                    else -> {
                        items(discoverUiState.items.chunked(3)) { rowItems ->
                            DiscoverGridRow(
                                items = rowItems,
                                modifier = Modifier.padding(horizontal = 16.dp),
                                onPosterClick = onPosterClick,
                            )
                        }
                        if (discoverUiState.isLoading) {
                            item {
                                CatalogLoadingFooter(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                        }
                    }
                }
            } else {
                when {
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
                            items = uiState.sections,
                            key = { section -> section.key },
                        ) { section ->
                            HomeCatalogRowSection(
                                section = section,
                                modifier = Modifier.padding(bottom = 12.dp),
                                onPosterClick = onPosterClick,
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp)
                .onSizeChanged { headerHeightPx = it.height },
        ) {
            NuvioScreenHeader(title = "Search")
            Spacer(modifier = Modifier.height(12.dp))
            NuvioInputField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search movies, shows...",
            )
        }
    }
}

@Composable
private fun DiscoverSectionHeader(modifier: Modifier = Modifier) {
    Text(
        text = "Discover",
        modifier = modifier,
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
private fun DiscoverFilterRow(
    state: DiscoverUiState,
    onTypeSelected: (String) -> Unit,
    onCatalogSelected: (String) -> Unit,
    onGenreSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DiscoverDropdownChip(
            label = state.selectedType?.displayTypeLabel() ?: "Type",
            options = state.typeOptions.map { DiscoverOptionItem(key = it, label = it.displayTypeLabel()) },
            enabled = state.typeOptions.isNotEmpty(),
            onSelected = { onTypeSelected(it.key) },
        )
        DiscoverDropdownChip(
            label = state.selectedCatalog?.catalogName ?: "Catalog",
            options = state.catalogOptions.map { option -> DiscoverOptionItem(key = option.key, label = option.catalogName) },
            enabled = state.catalogOptions.isNotEmpty(),
            onSelected = { onCatalogSelected(it.key) },
        )

        val selectedCatalog = state.selectedCatalog
        val genreOptions = buildList {
            if (selectedCatalog?.genreRequired != true) {
                add(DiscoverOptionItem(key = "", label = "All Genres"))
            }
            addAll(state.genreOptions.map { genre -> DiscoverOptionItem(key = genre, label = genre) })
        }
        DiscoverDropdownChip(
            label = state.selectedGenre ?: "All Genres",
            options = genreOptions,
            enabled = genreOptions.size > 1 || selectedCatalog?.genreRequired == true,
            onSelected = { option ->
                onGenreSelected(option.key.ifBlank { null })
            },
        )
    }
}

@Composable
private fun DiscoverDropdownChip(
    label: String,
    options: List<DiscoverOptionItem>,
    enabled: Boolean,
    onSelected: (DiscoverOptionItem) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .then(
                    if (enabled) {
                        Modifier.clickable { expanded = true }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            androidx.compose.material3.Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun DiscoverGridRow(
    items: List<MetaPreview>,
    modifier: Modifier = Modifier,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        items.forEach { item ->
            DiscoverPosterTile(
                item = item,
                modifier = Modifier.weight(1f),
                onClick = onPosterClick?.let { { it(item) } },
            )
        }
        repeat(3 - items.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun DiscoverPosterTile(
    item: MetaPreview,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(item.posterShape.discoverAspectRatio())
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            if (item.poster != null) {
                AsyncImage(
                    model = item.poster,
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Text(
            text = item.name,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val detail = item.releaseInfo ?: item.imdbRating?.let { "IMDb $it" }
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DiscoverSkeletonRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(0.68f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surface),
            )
        }
    }
}

@Composable
private fun CatalogLoadingFooter(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
        )
    }
}

@Composable
private fun DiscoverEmptyStateCard(
    reason: DiscoverEmptyStateReason?,
    errorMessage: String?,
    modifier: Modifier = Modifier,
) {
    val title: String
    val message: String

    when (reason) {
        DiscoverEmptyStateReason.NoActiveAddons -> {
            title = "No active addons"
            message = "Install and validate at least one addon before browsing discover catalogs."
        }

        DiscoverEmptyStateReason.NoDiscoverCatalogs -> {
            title = "No discover catalogs"
            message = "Installed addons do not expose board-compatible catalogs for discover."
        }

        DiscoverEmptyStateReason.RequestFailed -> {
            title = "Could not load discover"
            message = errorMessage ?: "The selected catalog failed to return discover items."
        }

        DiscoverEmptyStateReason.NoResults, null -> {
            title = "No titles found"
            message = "The selected catalog and filters did not return any items."
        }
    }

    HomeEmptyStateCard(
        modifier = modifier,
        title = title,
        message = message,
    )
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

private data class DiscoverOptionItem(
    val key: String,
    val label: String,
)

private fun String.displayTypeLabel(): String =
    when (lowercase()) {
        "movie" -> "Movies"
        "series" -> "Series"
        "anime" -> "Anime"
        "channel" -> "Channels"
        "tv" -> "TV"
        else -> replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

private fun PosterShape.discoverAspectRatio(): Float =
    when (this) {
        PosterShape.Poster -> 0.68f
        PosterShape.Square -> 1f
        PosterShape.Landscape -> 1.2f
    }
