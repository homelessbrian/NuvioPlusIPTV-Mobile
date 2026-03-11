package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension

internal fun LazyListScope.settingsRootContent(
    isTablet: Boolean,
    onContentDiscoveryClick: () -> Unit,
) {
    item {
        SettingsSection(
            title = "GENERAL",
            isTablet = isTablet,
        ) {
            SettingsNavigationRow(
                title = "Content & Discovery",
                description = "Manage addons and discovery sources.",
                icon = Icons.Rounded.Extension,
                isTablet = isTablet,
                onClick = onContentDiscoveryClick,
            )
        }
    }
}
