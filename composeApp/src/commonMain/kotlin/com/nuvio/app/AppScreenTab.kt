package com.nuvio.app

import com.nuvio.app.core.ui.NativeNavigationTab

enum class AppScreenTab {
    Home,
    Search,
    Library,
    LiveTv,
    Sports,
    Settings,
    ;

    companion object {
        fun fromName(name: String): AppScreenTab =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Home
    }
}

internal fun AppScreenTab.toNativeNavigationTab(): NativeNavigationTab = when (this) {
    AppScreenTab.Home -> NativeNavigationTab.Home
    AppScreenTab.Search -> NativeNavigationTab.Search
    AppScreenTab.Library -> NativeNavigationTab.Library
    AppScreenTab.LiveTv -> NativeNavigationTab.LiveTv
    AppScreenTab.Sports -> NativeNavigationTab.Sports
    AppScreenTab.Settings -> NativeNavigationTab.Settings
}

internal fun NativeNavigationTab.toAppScreenTab(): AppScreenTab = when (this) {
    NativeNavigationTab.Home -> AppScreenTab.Home
    NativeNavigationTab.Search -> AppScreenTab.Search
    NativeNavigationTab.Library -> AppScreenTab.Library
    NativeNavigationTab.LiveTv -> AppScreenTab.LiveTv
    NativeNavigationTab.Sports -> AppScreenTab.Sports
    NativeNavigationTab.Settings -> AppScreenTab.Settings
}

/** Keep the root selection reachable when a synced Sports visibility setting changes. */
internal fun sportsNavigationFallback(
    selectedTab: AppScreenTab,
    showSports: Boolean,
    showLiveTv: Boolean,
): AppScreenTab = if (selectedTab == AppScreenTab.Sports && !showSports) {
    if (showLiveTv) AppScreenTab.LiveTv else AppScreenTab.Home
} else selectedTab
