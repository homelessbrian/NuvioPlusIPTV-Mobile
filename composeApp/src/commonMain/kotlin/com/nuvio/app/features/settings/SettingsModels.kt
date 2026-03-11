package com.nuvio.app.features.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector

internal enum class SettingsCategory(
    val label: String,
    val icon: ImageVector,
) {
    General("General", Icons.Rounded.Settings),
}

internal enum class SettingsPage(
    val title: String,
) {
    Root("Settings"),
    ContentDiscovery("Content & Discovery"),
    Homescreen("Homescreen"),
}
