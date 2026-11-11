package com.nuvio.app.features.livetv.multiview

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.player.HidePlayerSystemBars
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Exercises the real modal sheet, theme, bounds, scrolling and asynchronous large-list search. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w800dp-h360dp-land-mdpi")
class MultiviewControlsSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val channels = List(16_000) { index -> channel(index + 1) }
    private var selection by mutableStateOf(
        MultiviewSelection(layout = MultiviewLayout.QUAD).assign(0, channels.first()).focus(0),
    )
    private var dismissed by mutableStateOf(false)
    private val layoutChanges = mutableListOf<MultiviewLayout>()
    private val assignments = mutableListOf<Pair<Int, String>>()
    private val clearedPanes = mutableListOf<Int>()
    private var dismissCount = 0

    @Test
    fun landscapePhoneRetainsVisibleRowsAndAllPickerActions() {
        exerciseSheet(minimumListHeight = 120.dp)
    }

    @Test
    @Config(qualifiers = "w800dp-h180dp-land-mdpi")
    fun syntheticImeShortWindowRetainsARowAndAllPickerActions() {
        // This changes available window bounds; it does not claim to exercise an actual IME.
        exerciseSheet(minimumListHeight = 56.dp)
    }

    private fun exerciseSheet(minimumListHeight: Dp) {
        compose.setContent {
            NuvioTheme {
                // Production opens this sheet from the existing immersive player route.
                HidePlayerSystemBars()
                if (!dismissed) {
                    MultiviewControlsSheet(
                        selection = selection,
                        initialPane = 0,
                        channels = channels,
                        onLayout = {
                            layoutChanges += it
                            selection = selection.withLayout(it)
                        },
                        onAssign = { pane, channel ->
                            assignments += pane to channel.key
                            selection = selection.assign(pane, channel)
                        },
                        onClear = { pane ->
                            clearedPanes += pane
                            selection = selection.assign(pane, null)
                        },
                        onDismiss = {
                            dismissCount++
                            dismissed = true
                        },
                    )
                }
            }
        }
        waitForChannel(channels.first().name)
        visibleList().assertHeightIsAtLeast(minimumListHeight)

        // Compact controls may be horizontally outside the viewport; users can scroll to all.
        listOf(MultiviewLayout.DUAL, MultiviewLayout.TRIPLE, MultiviewLayout.QUAD).forEach { layout ->
            clickVisible(compose.onNode(hasText(layout.title) and hasClickAction()))
        }
        compose.runOnIdle {
            assertEquals(listOf(MultiviewLayout.DUAL, MultiviewLayout.TRIPLE, MultiviewLayout.QUAD), layoutChanges)
            assertEquals(0, selection.focusedPane, "Layout controls must retain the selected video's audio")
        }
        clickVisible(compose.onNode(hasText("4") and hasClickAction()))
        compose.onNodeWithContentDescription("Clear pane 4").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Close Multiview controls").assertIsDisplayed().assertIsEnabled()

        // A hit at the end of the source proves the actual background index searches beyond
        // the initial visible/default result cap and routes selection to the chosen pane.
        val last = channels.last()
        compose.onNodeWithContentDescription("Search channels for pane 4")
            .assertIsDisplayed().performTextInput(last.name)
        waitForChannel(last.name)
        visibleList().assertHeightIsAtLeast(minimumListHeight)
        clickVisible(compose.onNode(channelRow(last.name)))
        compose.runOnIdle {
            assertEquals(listOf(3 to last.key), assignments)
            assertEquals(0, selection.focusedPane, "Picking another stream must preserve video audio focus")
            assertEquals(0, dismissCount)
        }

        // Clear and Close must invoke distinct actions. Clearing also stays in the picker.
        compose.onNodeWithContentDescription("Clear pane 4").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Close Multiview controls").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(3), clearedPanes)
            assertEquals(0, dismissCount)
            assertEquals(null, selection.channels[3])
        }
        compose.onNodeWithContentDescription("Clear pane 4").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Close Multiview controls").performClick()
        compose.runOnIdle {
            assertEquals(1, dismissCount)
            assertEquals(listOf(3), clearedPanes)
        }
        compose.onNodeWithContentDescription("Close Multiview controls").assertDoesNotExist()
    }

    private fun visibleList() = compose.onNode(hasScrollToIndexAction())

    private fun channelRow(name: String) =
        hasText(name) and hasClickAction() and hasAnyAncestor(hasScrollToIndexAction())

    private fun clickVisible(node: SemanticsNodeInteraction) {
        // A one-result list can fit entirely and correctly omit scrolling semantics.
        if (!node.isDisplayed()) node.performScrollTo()
        node.assertIsDisplayed().performClick()
    }

    private fun waitForChannel(name: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(channelRow(name)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun channel(number: Int) = LiveChannel(
        key = "synthetic-$number",
        sourceId = "synthetic",
        sourceName = "Synthetic playlist",
        name = "Synthetic channel ${number.toString().padStart(5, '0')}",
        tvgId = null,
        tvgName = null,
        logo = null,
        groupId = "synthetic:sports",
        group = "Synthetic sports",
        number = number,
        url = "https://example.invalid/live/$number.m3u8",
    )
}
