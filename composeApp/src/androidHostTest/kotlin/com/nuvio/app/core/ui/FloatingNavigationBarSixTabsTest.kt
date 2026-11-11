package com.nuvio.app.core.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nuvio.app.AppScreenTab
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingNavigationBarSixTabsTest {
    @get:Rule val compose = createComposeRule()
    private val selected = mutableStateOf(AppScreenTab.LiveTv)
    private val direction = mutableStateOf(LayoutDirection.Ltr)
    private val collapsed = mutableStateOf(false)

    @Test
    fun allSixTabsRemainVisibleAndTappableAt320DpInBothDirections() {
        compose.setContent {
            NuvioTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                    val scroll = rememberNuvioNavBarScrollState()
                    if (collapsed.value) scroll.collapse() else scroll.expand()
                    val items = AppScreenTab.entries.map { tab ->
                        FloatingNavigationItem(
                            label = tab.name,
                            selected = selected.value == tab,
                            onClick = { selected.value = tab },
                            icon = Icons.Filled.Home,
                        )
                    }
                    Box(Modifier.width(320.dp).height(100.dp)) {
                        FloatingNavigationBar(items = items, scrollState = scroll, contentPadding = PaddingValues(0.dp))
                    }
                }
            }
        }
        for (rtl in listOf(false, true)) for (compact in listOf(false, true)) {
            compose.runOnIdle {
                direction.value = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                collapsed.value = compact
            }
            compose.waitForIdle()
            for (tab in AppScreenTab.entries) {
                val node = compose.onNodeWithContentDescription(tab.name)
                node.assertIsDisplayed()
                assertTrue(node.fetchSemanticsNode().boundsInRoot.width >= 47.9f, "48dp touch slot for $tab")
                node.performTouchInput { click() }
                compose.runOnIdle { assertEquals(tab, selected.value) }
            }
            val liveTvX = compose.onNodeWithContentDescription(AppScreenTab.LiveTv.name).fetchSemanticsNode().boundsInRoot.center.x
            val sportsX = compose.onNodeWithContentDescription(AppScreenTab.Sports.name).fetchSemanticsNode().boundsInRoot.center.x
            val profileX = compose.onNodeWithContentDescription(AppScreenTab.Settings.name).fetchSemanticsNode().boundsInRoot.center.x
            assertTrue(if (rtl) liveTvX > sportsX && sportsX > profileX else liveTvX < sportsX && sportsX < profileX)
        }
    }

    @Test
    fun classicSixTabsKeepTheirIconSizeAndTouchTargetsAt320Dp() {
        compose.setContent {
            NuvioTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                    Box(Modifier.width(320.dp)) {
                        NuvioClassicNavigationBar(itemCount = AppScreenTab.entries.size) {
                            AppScreenTab.entries.forEach { tab ->
                                NavItem(
                                    selected = selected.value == tab,
                                    onClick = { selected.value = tab },
                                    icon = Icons.Filled.Home,
                                    contentDescription = tab.name,
                                )
                            }
                        }
                    }
                }
            }
        }
        for (layoutDirection in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            compose.runOnIdle { direction.value = layoutDirection }
            compose.waitForIdle()
            for (tab in AppScreenTab.entries) {
                val node = compose.onNodeWithContentDescription(tab.name)
                node.assertIsDisplayed()
                assertTrue(node.fetchSemanticsNode().boundsInRoot.width >= 51.9f, "32dp icon plus20dp padding for $tab")
                node.performTouchInput { click() }
                compose.runOnIdle { assertEquals(tab, selected.value) }
            }
        }
    }
}
