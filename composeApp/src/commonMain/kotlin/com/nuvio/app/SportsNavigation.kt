package com.nuvio.app

import com.nuvio.app.navigation.LiveTvSportsGameRoute
import com.nuvio.app.navigation.LiveTvSportsRoute
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.TabsRoute

/** Android opens the persistent Sports pane; hidden/native entries keep their existing route. */
internal fun NuvioNavigator.openSportsRoot(
    showSportsTab: Boolean,
    useNativeNavigation: Boolean,
    title: String,
    activateTab: (AppScreenTab) -> Unit,
) {
    if (useNativeNavigation || !showSportsTab) {
        activateTab(AppScreenTab.LiveTv)
        navigate(LiveTvSportsRoute(title)) { launchSingleTop = true }
    } else {
        activateTab(AppScreenTab.Sports)
        navigate(TabsRoute) {
            popUpTo<TabsRoute>()
            launchSingleTop = true
        }
    }
}

internal fun NuvioNavigator.openSportsGameNotification(
    eventId: String,
    title: String,
    showSportsTab: Boolean,
    useNativeNavigation: Boolean,
    activateTab: (AppScreenTab) -> Unit,
) {
    if (useNativeNavigation || !showSportsTab) {
        activateTab(AppScreenTab.LiveTv)
    } else {
        openSportsRoot(showSportsTab, useNativeNavigation, title, activateTab)
    }
    navigate(LiveTvSportsGameRoute(eventId, title)) { launchSingleTop = true }
}
