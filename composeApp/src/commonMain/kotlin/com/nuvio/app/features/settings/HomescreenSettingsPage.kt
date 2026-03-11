package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import com.nuvio.app.features.home.HomeCatalogSettingsItem
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.components.HomeEmptyStateCard

internal fun LazyListScope.homescreenSettingsContent(
    isTablet: Boolean,
    heroEnabled: Boolean,
    items: List<HomeCatalogSettingsItem>,
) {
    item {
        SettingsSection(
            title = "HERO",
            isTablet = isTablet,
        ) {
            SettingsSwitchRow(
                title = "Show Hero",
                description = "Display a featured hero carousel at the top of Home.",
                checked = heroEnabled,
                isTablet = isTablet,
                onCheckedChange = HomeCatalogSettingsRepository::setHeroEnabled,
            )
        }
    }
    item {
        if (items.isNotEmpty()) {
            SettingsSection(
                title = "HERO SOURCES",
                isTablet = isTablet,
            ) {
                items.forEach { item ->
                    SettingsSwitchRow(
                        title = item.displayTitle,
                        description = item.addonName,
                        checked = item.heroSourceEnabled,
                        isTablet = isTablet,
                        onCheckedChange = { HomeCatalogSettingsRepository.setHeroSourceEnabled(item.key, it) },
                    )
                }
            }
        }
    }
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
                }
            }
        }
    }
}
