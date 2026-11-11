package com.nuvio.app

import com.nuvio.app.core.ui.NativeNavigationTab
import com.nuvio.app.navigation.LiveTvSportsGameRoute
import com.nuvio.app.navigation.LiveTvSportsRoute
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.PlayerRoute
import com.nuvio.app.navigation.TabsRoute
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlin.test.Test
import kotlin.test.assertEquals

class SportsNavigationTest {
    @Test
    fun sportsHasAStableNameAndSitsImmediatelyAfterLiveTv() {
        val tabs = AppScreenTab.entries
        assertEquals(AppScreenTab.Sports, tabs[tabs.indexOf(AppScreenTab.LiveTv) + 1])
        assertEquals(AppScreenTab.Sports, AppScreenTab.fromName("Sports"))
        assertEquals(AppScreenTab.Sports, AppScreenTab.fromName("sports"))
        assertEquals(NativeNavigationTab.Sports, NativeNavigationTab.fromName("Sports"))
        for (tab in tabs) assertEquals(tab, tab.toNativeNavigationTab().toAppScreenTab())
    }

    @Test
    fun hidingSelectedSportsFallsBackToLiveTvOrHome() {
        assertEquals(AppScreenTab.LiveTv, sportsNavigationFallback(AppScreenTab.Sports, false, true))
        assertEquals(AppScreenTab.Home, sportsNavigationFallback(AppScreenTab.Sports, false, false))
        assertEquals(AppScreenTab.Sports, sportsNavigationFallback(AppScreenTab.Sports, true, false))
    }

    @Test
    fun hidingSportsDoesNotChangeOtherRootSelections() {
        for (tab in AppScreenTab.entries.filter { it != AppScreenTab.Sports }) {
            assertEquals(tab, sportsNavigationFallback(tab, false, false))
        }
    }

    @Test
    fun sportsEntryReturnsToTheRootWithoutDuplicatingIt() {
        val stack = NavBackStack<NavKey>(TabsRoute, LiveTvSportsRoute("Sports"))
        val nav = NuvioNavigator(stack)
        var selected = AppScreenTab.LiveTv
        repeat(2) { nav.openSportsRoot(true, false, "Sports") { selected = it } }
        assertEquals(AppScreenTab.Sports, selected)
        assertEquals(listOf(TabsRoute), nav.routes)
    }

    @Test
    fun gameNotificationAndPlayerBackReturnToSports() {
        val nav = NuvioNavigator(NavBackStack<NavKey>(TabsRoute))
        var selected = AppScreenTab.Home
        nav.openSportsGameNotification("game", "Sports", true, false) { selected = it }
        val game = LiveTvSportsGameRoute("game", "Sports")
        val player = PlayerRoute(1L)
        nav.navigate(player)
        nav.popBackStack(player)
        assertEquals(game, nav.currentRoute)
        nav.popBackStack(game)
        assertEquals(TabsRoute, nav.currentRoute)
        assertEquals(AppScreenTab.Sports, selected)
    }

    @Test
    fun hiddenSportsAndNativeShellRetainLegacySportsAccess() {
        for ((showSports, native) in listOf(false to false, true to true)) {
            val nav = NuvioNavigator(NavBackStack<NavKey>(TabsRoute))
            var selected = AppScreenTab.Home
            nav.openSportsRoot(showSports, native, "Sports") { selected = it }
            assertEquals(AppScreenTab.LiveTv, selected)
            assertEquals(LiveTvSportsRoute("Sports"), nav.currentRoute)
        }
        val nav = NuvioNavigator(NavBackStack<NavKey>(TabsRoute))
        var selected = AppScreenTab.Home
        nav.openSportsGameNotification("hidden", "Sports", false, false) { selected = it }
        assertEquals(AppScreenTab.LiveTv, selected)
        assertEquals(listOf(TabsRoute, LiveTvSportsGameRoute("hidden", "Sports")), nav.routes)
    }
}
