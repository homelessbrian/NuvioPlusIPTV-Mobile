package com.nuvio.app.features.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.home.HomeCatalogSettingsItem
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class SettingsCategory(
    val label: String,
    val icon: ImageVector,
) {
    General("General", Icons.Rounded.Settings),
}

private enum class SettingsPage(
    val title: String,
) {
    Root("Settings"),
    ContentDiscovery("Content & Discovery"),
    Homescreen("Homescreen"),
}

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onAddonsClick: () -> Unit = {},
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LaunchedEffect(Unit) {
            AddonRepository.initialize()
        }

        val addonsUiState by AddonRepository.uiState.collectAsStateWithLifecycle()
        val homescreenSettingsUiState by HomeCatalogSettingsRepository.uiState.collectAsStateWithLifecycle()

        LaunchedEffect(addonsUiState.addons) {
            HomeCatalogSettingsRepository.syncCatalogs(addonsUiState.addons)
        }

        var currentPage by rememberSaveable { mutableStateOf(SettingsPage.Root.name) }
        val page = remember(currentPage) { SettingsPage.valueOf(currentPage) }

        if (maxWidth >= 768.dp) {
            TabletSettingsScreen(
                page = page,
                onPageChange = { currentPage = it.name },
                onAddonsClick = onAddonsClick,
                homescreenSettings = homescreenSettingsUiState.items,
            )
        } else {
            MobileSettingsScreen(
                page = page,
                onPageChange = { currentPage = it.name },
                onAddonsClick = onAddonsClick,
                homescreenSettings = homescreenSettingsUiState.items,
            )
        }
    }
}

@Composable
private fun MobileSettingsScreen(
    page: SettingsPage,
    onPageChange: (SettingsPage) -> Unit,
    onAddonsClick: () -> Unit,
    homescreenSettings: List<HomeCatalogSettingsItem>,
) {
    NuvioScreen {
        stickyHeader {
            NuvioScreenHeader(
                title = page.title,
                onBack = if (page != SettingsPage.Root) {
                    { onPageChange(SettingsPage.Root) }
                } else {
                    null
                },
            )
        }

        when (page) {
            SettingsPage.Root -> settingsRootContent(
                isTablet = false,
                onContentDiscoveryClick = { onPageChange(SettingsPage.ContentDiscovery) },
            )
            SettingsPage.ContentDiscovery -> contentDiscoveryContent(
                isTablet = false,
                onAddonsClick = onAddonsClick,
                onHomescreenClick = { onPageChange(SettingsPage.Homescreen) },
            )
            SettingsPage.Homescreen -> homescreenSettingsContent(
                isTablet = false,
                items = homescreenSettings,
            )
        }
    }
}

@Composable
private fun TabletSettingsScreen(
    page: SettingsPage,
    onPageChange: (SettingsPage) -> Unit,
    onAddonsClick: () -> Unit,
    homescreenSettings: List<HomeCatalogSettingsItem>,
) {
    var selectedCategory by rememberSaveable { mutableStateOf(SettingsCategory.General.name) }
    val activeCategory = SettingsCategory.valueOf(selectedCategory)
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topOffset = max(statusBarPadding + 24.dp, 48.dp) + 64.dp

    Row(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .width(280.dp)
                .fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topOffset),
            ) {
                Text(
                    text = "Settings",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 20.dp),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Spacer(modifier = Modifier.height(10.dp))
                SettingsSidebarItem(
                    label = activeCategory.label,
                    icon = activeCategory.icon,
                    selected = true,
                    onClick = { selectedCategory = activeCategory.name },
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 40.dp,
                top = topOffset,
                end = 40.dp,
                bottom = 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                TabletPageHeader(
                    title = if (page == SettingsPage.Root) activeCategory.label else page.title,
                    showBack = page != SettingsPage.Root,
                    onBack = { onPageChange(SettingsPage.Root) },
                )
            }
            when (page) {
                SettingsPage.Root -> settingsRootContent(
                    isTablet = true,
                    onContentDiscoveryClick = { onPageChange(SettingsPage.ContentDiscovery) },
                )
                SettingsPage.ContentDiscovery -> contentDiscoveryContent(
                    isTablet = true,
                    onAddonsClick = onAddonsClick,
                    onHomescreenClick = { onPageChange(SettingsPage.Homescreen) },
                )
                SettingsPage.Homescreen -> homescreenSettingsContent(
                    isTablet = true,
                    items = homescreenSettings,
                )
            }
        }
    }
}

private fun LazyListScope.settingsRootContent(
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

private fun LazyListScope.contentDiscoveryContent(
    isTablet: Boolean,
    onAddonsClick: () -> Unit,
    onHomescreenClick: () -> Unit,
) {
    item {
        SettingsSection(
            title = "SOURCES",
            isTablet = isTablet,
        ) {
            SettingsNavigationRow(
                title = "Addons",
                description = "Install, remove, refresh, and sort your content sources.",
                icon = Icons.Rounded.Extension,
                isTablet = isTablet,
                onClick = onAddonsClick,
            )
        }
    }
    item {
        SettingsSection(
            title = "HOME",
            isTablet = isTablet,
        ) {
            SettingsNavigationRow(
                title = "Homescreen",
                description = "Control which catalogs appear on Home and in what order.",
                icon = Icons.Rounded.Tune,
                isTablet = isTablet,
                onClick = onHomescreenClick,
            )
        }
    }
}

private fun LazyListScope.homescreenSettingsContent(
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

@Composable
private fun TabletPageHeader(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showBack) {
            Surface(
                modifier = Modifier
                    .size(36.dp)
                    .clickable(onClick = onBack),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SettingsSidebarItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val background = if (selected) primary.copy(alpha = 0.10f) else Color.Transparent
    val iconChip = if (selected) primary.copy(alpha = 0.15f) else Color.Transparent
    val contentColor = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .background(background, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(32.dp),
            color = iconChip,
            shape = RoundedCornerShape(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) primary else contentColor,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    isTablet: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        NuvioSectionLabel(text = title)
        Spacer(modifier = Modifier.height(if (isTablet) 12.dp else 10.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(if (isTablet) 20.dp else 16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsNavigationRow(
    title: String,
    description: String,
    icon: ImageVector,
    isTablet: Boolean,
    onClick: () -> Unit,
) {
    val iconSize = if (isTablet) 42.dp else 36.dp
    val iconRadius = if (isTablet) 12.dp else 10.dp
    val verticalPadding = if (isTablet) 16.dp else 14.dp
    val horizontalPadding = if (isTablet) 20.dp else 16.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .padding(end = 12.dp)
                .widthIn(max = if (isTablet) 560.dp else 320.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(iconSize),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                shape = RoundedCornerShape(iconRadius),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(modifier = Modifier.width(if (isTablet) 16.dp else 14.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alpha(0.92f),
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomescreenCatalogRow(
    item: HomeCatalogSettingsItem,
    isTablet: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onTitleChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 18.dp else 16.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .widthIn(max = if (isTablet) 560.dp else 260.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.displayTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.addonName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = item.enabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
        }

        OutlinedTextField(
            value = item.customTitle,
            onValueChange = onTitleChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = {
                Text("Display Name")
            },
            placeholder = {
                Text(item.defaultTitle)
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MoveActionChip(
                label = "Move Up",
                icon = Icons.Rounded.KeyboardArrowUp,
                enabled = canMoveUp,
                onClick = onMoveUp,
            )
            MoveActionChip(
                label = "Move Down",
                icon = Icons.Rounded.KeyboardArrowDown,
                enabled = canMoveDown,
                onClick = onMoveDown,
            )
        }
    }
}

@Composable
private fun MoveActionChip(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        shape = RoundedCornerShape(999.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
