package com.nuvio.app.features.livetv.ui.sports

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import com.nuvio.app.AppScreenTab
import com.nuvio.app.RootTabHost
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SportsRootActivityTest {
    @get:Rule val compose = createComposeRule()
    private val selected = mutableStateOf(AppScreenTab.Sports)
    private val active = mutableStateOf(true)
    private val profile = mutableStateOf(1)
    private val mounted = mutableSetOf<AppScreenTab>()
    private val changes = mutableListOf<Boolean>()
    private var viewers = 0
    private val updateViewing: (Boolean) -> Unit = { on ->
        changes += on
        viewers += if (on) 1 else -1
        check(viewers >= 0)
    }

    @Test
    fun sportsCanBeFirstTabWithoutMountingLiveTvAndDoesNotStayViewingWhenHidden() {
        show()
        compose.runOnIdle {
            assertEquals(1, viewers)
            assertEquals(listOf(true), changes)
            assertFalse(AppScreenTab.LiveTv in mounted)
            selected.value = AppScreenTab.LiveTv
        }
        compose.runOnIdle {
            assertEquals(0, viewers)
            assertEquals(listOf(true, false), changes)
            selected.value = AppScreenTab.Sports
        }
        compose.runOnIdle {
            assertEquals(1, viewers)
            assertEquals(listOf(true, false, true), changes)
        }
    }

    @Test
    fun openingAGameReleasesOnlyTheRootViewerAndReturningReacquiresIt() {
        show()
        compose.runOnIdle {
            viewers++ // The existing game page owns its independent viewer lease.
            active.value = false
        }
        compose.runOnIdle {
            assertEquals(1, viewers)
            viewers--
            active.value = true
        }
        compose.runOnIdle { assertEquals(1, viewers) }
    }

    @Test
    fun profileSwitchReleasesTheOldSportsPaneBeforeAcquiringTheNewOne() {
        show()
        compose.runOnIdle { profile.value = 2 }
        compose.runOnIdle {
            assertEquals(1, viewers)
            assertEquals(listOf(true, false, true), changes)
        }
    }

    private fun show() {
        compose.setContent {
            RootTabHost(selected.value, active = active.value, profileId = profile.value) { tab ->
                mounted += tab
                if (tab == AppScreenTab.Sports) SportsViewingEffect(updateViewing)
                Box {}
            }
        }
        compose.waitForIdle()
    }
}
