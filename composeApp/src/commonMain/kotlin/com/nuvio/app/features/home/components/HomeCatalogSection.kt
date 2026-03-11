package com.nuvio.app.features.home.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioViewAllPillSize
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview

@Composable
fun HomeCatalogRowSection(
    section: HomeCatalogSection,
    modifier: Modifier = Modifier,
    onViewAllClick: (() -> Unit)? = null,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
) {
    NuvioShelfSection(
        title = section.title,
        entries = section.items,
        modifier = modifier,
        headerHorizontalPadding = 16.dp,
        rowContentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        onViewAllClick = onViewAllClick,
        viewAllPillSize = NuvioViewAllPillSize.Compact,
        key = { item -> item.id },
    ) { item ->
        HomePosterCard(
            item = item,
            onClick = onPosterClick?.let { { it(item) } },
        )
    }
}
