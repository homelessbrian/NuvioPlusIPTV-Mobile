package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Style

internal fun LazyListScope.appearanceSettingsContent(
    isTablet: Boolean,
    onContinueWatchingClick: () -> Unit,
) {
    item {
        SettingsSection(
            title = "HOME",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = "Continue Watching",
                    description = "Show, hide, and style the Continue Watching shelf.",
                    icon = Icons.Rounded.Style,
                    isTablet = isTablet,
                    onClick = onContinueWatchingClick,
                )
            }
        }
    }
}
