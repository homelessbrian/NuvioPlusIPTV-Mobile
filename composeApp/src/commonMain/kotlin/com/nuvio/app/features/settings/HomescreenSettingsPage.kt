package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import com.nuvio.app.features.home.HomeCatalogSettingsItem
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.components.HomeEmptyStateCard

internal fun LazyListScope.homescreenSettingsContent(
    isTablet: Boolean,
    items: List<HomeCatalogSettingsItem>,
) {
    item {
        if (items.isEmpty()) {
            HomeEmptyStateCard(
                modifier = Modifier.fillMaxWidth(),
                title = "No home catalogs",
                message = "Install an addon with board-compatible catalogs to configure Homescreen rows.",
            )
        } else {
            SettingsSection(
                title = "CATALOGS",
                isTablet = isTablet,
            ) {
                items.forEachIndexed { index, item ->
                    HomescreenCatalogRow(
                        item = item,
                        isTablet = isTablet,
                        canMoveUp = index > 0,
                        canMoveDown = index < items.lastIndex,
                        onTitleChange = { HomeCatalogSettingsRepository.setCustomTitle(item.key, it) },
                        onEnabledChange = { HomeCatalogSettingsRepository.setEnabled(item.key, it) },
                        onMoveUp = { HomeCatalogSettingsRepository.moveUp(item.key) },
                        onMoveDown = { HomeCatalogSettingsRepository.moveDown(item.key) },
                    )
                    if (index < items.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}
