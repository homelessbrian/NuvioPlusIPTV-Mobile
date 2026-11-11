package com.nuvio.app.features.livetv.multiview

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.sports.ChannelMatch
import com.nuvio.app.features.livetv.sports.GameState
import com.nuvio.app.features.livetv.sports.SportsEvent
import com.nuvio.app.features.livetv.sports.SportsWatchGame
import com.nuvio.app.features.livetv.sports.SportsWatchRow
import com.nuvio.app.features.livetv.ui.sports.MobileSportsGameStrip
import com.nuvio.app.features.player.HidePlayerSystemBars
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real picker gestures and callbacks; no provider request or native decoder is involved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w800dp-h360dp-land-mdpi")
class MobileSportsGameStripTest {
    @get:Rule val compose = createComposeRule()

    private val alpha = game("alpha", "Alpha final", "Alpha live", "Alpha alternate")
    private val beta = game("beta", "Beta championship", "Beta live",
        "Alternate Beta broadcast with extended channel label")
    private val games = listOf(alpha, beta)
    private val rows = listOf(
        SportsWatchRow("live", "Live Now", listOf(alpha)),
        SportsWatchRow("following", "Following", listOf(beta)),
    )
    private val session = MultiviewSession(profileId = 1)
    private var targetPane by mutableIntStateOf(0)
    private val paneTargets = mutableListOf<Int>()
    private val openedGames = mutableListOf<Pair<Int, String>>()
    private val broadcasts = mutableListOf<Triple<Int, String, String>>()
    private val played = mutableListOf<Pair<Int, String>>()
    private val removedPanes = mutableListOf<Int>()
    private var additions = 0

    @Test
    fun tappingAGamePlaysItsBestStreamAndHoldingItShowsTheOtherStreams() {
        showStrip()
        choosePane(1)
        button("Games").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(1), paneTargets)
            assertTrue(openedGames.isEmpty())
            assertTrue(broadcasts.isEmpty())
        }

        // Tap: the game's best stream plays in the chosen pane straight away.
        chooseBeta()
        compose.runOnIdle {
            assertEquals(listOf(1 to beta.event.id), played)
            assertTrue(openedGames.isEmpty(), "A tap plays the game; it does not open its streams")
            assertEquals(beta.channels[0].channel.key, session.selection.value.channels[1]?.key)
        }

        // Hold: the game's streams, and only a stream tap tunes one of them.
        holdBeta()
        button("Games").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { assertEquals(listOf(1 to beta.event.id), openedGames) }
        touchListText(beta.channels[1].channel.name)
        compose.runOnIdle {
            assertEquals(listOf(Triple(1, beta.event.id, beta.channels[1].channel.key)), broadcasts)
            assertEquals(beta.channels[1].channel.key, session.selection.value.channels[1]?.key)
        }

        // Games goes back to the list of games.
        button("Games").performClick()
        button("Games").assertDoesNotExist()
        assertPickerButtons()
    }

    @Test
    @Config(qualifiers = "w800dp-h180dp-land-mdpi")
    fun shortLandscapeKeepsPickerAndPaneActionsReachableWithScrollableBroadcastDetails() {
        // Synthetic available bounds, matching the existing editor host test; not a real IME.
        showStrip()
        choosePane(1)
        holdBeta()
        assertPickerButtons()

        // Metadata can scroll inside a short card; it must remain reachable and actionable.
        touchListText(beta.channels[1].how, scrollFully = true)
        compose.runOnIdle {
            assertEquals(listOf(Triple(1, beta.event.id, beta.channels[1].channel.key)), broadcasts)
        }
        button("Games").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Add pane").performClick()
        compose.runOnIdle {
            assertEquals(1, additions)
            assertEquals(3, session.selection.value.layout.paneCount)
            assertEquals(2, targetPane)
        }
        button("3").assertIsDisplayed()
        assertPickerButtons()
        compose.onNodeWithContentDescription("Remove selected pane").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(2), removedPanes)
            assertEquals(2, session.selection.value.layout.paneCount)
            assertEquals(1, broadcasts.size, "Pane controls must not tune another broadcast")
            assertTrue(played.isEmpty(), "Pane controls must not play a game")
        }
        button("3").assertDoesNotExist()
        assertPickerButtons()
    }

    @Test
    fun removingAnEarlierTargetKeepsStreamChoicesOnTheSurvivingPane() {
        showStrip(panes = 3)
        choosePane(2)
        holdBeta()
        compose.runOnIdle { assertTrue(broadcasts.isEmpty()) }

        choosePane(0)
        compose.onNodeWithContentDescription("Remove selected pane").performClick()
        compose.runOnIdle {
            assertEquals(listOf(0), removedPanes)
            assertEquals(2, session.selection.value.layout.paneCount)
        }
        // Old pane 3 is now pane 2: holding the game there and picking a stream tunes that pane.
        choosePane(1)
        holdBeta()
        touchListText(beta.channels[1].channel.name)
        compose.runOnIdle {
            assertEquals(listOf(2 to beta.event.id, 1 to beta.event.id), openedGames)
            assertEquals(listOf(Triple(1, beta.event.id, beta.channels[1].channel.key)), broadcasts)
        }
    }

    private fun showStrip(panes: Int = 2) {
        session.setLayout(MultiviewLayout.entries.first { it.paneCount == panes })
        session.assign(0, alpha.channels.first().channel)
        session.focus(0)
        compose.setContent {
            NuvioTheme {
                HidePlayerSystemBars()
                val selection by session.selection.collectAsState()
                val channelKey = selection.channels[targetPane]?.key
                MobileSportsGameStrip(
                    games = games,
                    rows = rows,
                    selectedEventId = games.firstOrNull { game -> game.channels.any { it.channel.key == channelKey } }?.event?.id,
                    paneChannelKeys = selection.channels.take(selection.layout.paneCount).map { it?.key },
                    focusedPane = targetPane,
                    paneCount = selection.layout.paneCount,
                    hideScores = false,
                    onGame = { game -> openedGames += targetPane to game.event.id; true },
                    onPlayGame = { game ->
                        played += targetPane to game.event.id
                        session.assign(targetPane, game.channels.first().channel)
                        session.focus(targetPane)
                        true
                    },
                    onStream = { game, key ->
                        broadcasts += Triple(targetPane, game.event.id, key)
                        session.assign(targetPane, game.channels.single { it.channel.key == key }.channel)
                        session.focus(targetPane)
                    },
                    onAdd = {
                        additions++
                        val count = session.selection.value.layout.paneCount
                        session.setLayout(MultiviewLayout.entries.first { it.paneCount == count + 1 })
                        targetPane = count
                    },
                    onRemove = {
                        removedPanes += targetPane
                        session.removePane(targetPane)
                        targetPane = targetPane.coerceAtMost(session.selection.value.layout.paneCount - 1)
                    },
                    onPane = { targetPane = it; paneTargets += it },
                    onClose = {},
                )
            }
        }
    }

    private fun choosePane(index: Int) = button("${index + 1}").assertIsDisplayed().performClick()

    private fun scrollToBeta() {
        // Exercise a later ordered row, rather than relying on offscreen lazy children existing.
        compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToIndex(1)
    }

    private fun chooseBeta() {
        scrollToBeta()
        touchListText(beta.event.title)
    }

    private fun holdBeta() {
        scrollToBeta()
        val node = compose.onNode(hasText(beta.event.title) and hasAnyAncestor(hasScrollToIndexAction()), useUnmergedTree = true)
        if (!node.isDisplayed()) node.performScrollTo()
        node.assertIsDisplayed().performTouchInput { longClick() }
    }

    private fun assertPickerButtons() {
        compose.onNodeWithContentDescription("Add pane").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove selected pane").assertIsDisplayed()
    }

    private fun button(text: String): SemanticsNodeInteraction = compose.onNode(hasText(text) and hasClickAction())

    private fun touchListText(text: String, scrollFully: Boolean = false) {
        val node = compose.onNode(hasText(text) and hasAnyAncestor(hasScrollToIndexAction()), useUnmergedTree = true)
        if (scrollFully || !node.isDisplayed()) node.performScrollTo()
        node.assertIsDisplayed().performTouchInput { click() }
    }

    private fun game(id: String, title: String, primary: String, alternate: String): SportsWatchGame = SportsWatchGame(
        SportsEvent(id, "fixture-league", 1_800_000_000_000L, true, GameState.LIVE, title, null, "Live", null, null),
        listOf(primary, alternate).mapIndexed { index, name ->
            ChannelMatch(
                LiveChannel(key = "$id-$index", sourceId = "fixture", sourceName = "Fixture playlist",
                    name = name, tvgId = null, tvgName = null, logo = null, groupId = "fixture:sports",
                    group = "Sports", number = index + 1, url = "https://example.invalid/$id/$index.m3u8"),
                score = 100, how = "$title broadcast ${index + 1} details", program = null, quality = "HD", catchup = false,
            )
        },
    )
}
