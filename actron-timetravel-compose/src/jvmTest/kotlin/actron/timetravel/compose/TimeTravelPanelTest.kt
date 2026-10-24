@file:OptIn(actron.core.ExperimentalActronApi::class, androidx.compose.ui.test.ExperimentalTestApi::class)

package actron.timetravel.compose

import actron.timetravel.inspect.Inspector
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeTravelPanelTest {
    private fun inspector(replay: GroupReplayControls) =
        InspectorState(
            Inspector.of(GroupReplayFixture.journal(replay.session.recording), replay.session.recording.members),
        )

    @Test
    fun compactHost_keepsPreviewVisible_andFilteringDoesNotMoveTheGroup() =
        runComposeUiTest {
            val replay = GroupReplayFixture.controls()
            val state = inspector(replay)
            setContent {
                MaterialTheme {
                    Column(Modifier.width(340.dp).height(500.dp)) {
                        Text("Preview: ${replay.snapshot(GroupReplayFixture.b).context}", Modifier.testTag("app-preview"))
                        TimeTravelPanel(state, groupReplay = replay, modifier = Modifier.height(300.dp))
                    }
                }
            }
            onNodeWithTag("app-preview").assertIsDisplayed()
            onNodeWithTag("panel-back").assertIsNotEnabled()
            repeat(4) { onNodeWithTag("panel-forward").performClick() }
            onNodeWithTag("panel-position").assertTextContains("4 / 6")
            onNodeWithTag("app-preview").assertTextContains("Ctx(n=11)", substring = true)
            onNodeWithTag("panel-machines").performClick()
            onNodeWithTag("panel-machine-chat-7").performClick()
            assertEquals(4, replay.position)
            onNodeWithTag("panel-step-3").assertDoesNotExist()
            onNodeWithTag("panel-step-2").performClick()
            assertEquals(3, replay.position)
            onNodeWithTag("app-preview").assertTextContains("Ctx(n=10)", substring = true)
            onNodeWithTag("panel-details-toggle").performClick()
            onNodeWithTag("panel-selected-snapshot").performScrollTo().assertTextContains("Ctx(n=1)", substring = true)
        }

    @Test
    fun divergence_isVisibleWithoutOpeningDetails_andStopsForwardMovement() =
        runComposeUiTest {
            val replay = GroupReplayFixture.controls(changed = true)
            setContent { MaterialTheme { TimeTravelPanel(inspector(replay), groupReplay = replay, modifier = Modifier.width(340.dp).height(300.dp)) } }
            repeat(4) { onNodeWithTag("panel-forward").performClick() }
            onNodeWithTag("panel-forward").assertIsNotEnabled()
            onNodeWithTag("panel-problem").performScrollTo().assertTextContains("root-2", substring = true)
            assertEquals(3, replay.position)
            onNodeWithTag("panel-back").performClick()
            onNodeWithTag("panel-problem").assertDoesNotExist()
        }

    @Test
    fun experiment_changesOnlyItsBranch_andReturningKeepsTheReplayCursor() =
        runComposeUiTest {
            val replay = GroupReplayFixture.controls().also { it.seek(4) }
            val travel = GroupTimeTravelControls(replay, inputs = listOf(BranchInput.Dispatch(GroupReplayFixture.b, "Increment", GroupReplayFixture.Inc)))
            setContent { MaterialTheme { TimeTravelPanel(inspector(replay), timeTravel = travel, modifier = Modifier.width(340.dp).height(300.dp)) } }
            onNodeWithTag("panel-machines").performClick()
            onNodeWithTag("panel-machine-root-2").performClick()
            onNodeWithTag("panel-branch").performClick()
            onNodeWithTag("panel-mode").assertTextContains("Experiment")
            onNodeWithTag("panel-forward").assertDoesNotExist()
            onNodeWithTag("panel-input-root-2-Increment").performScrollTo().performClick()
            onNodeWithTag("panel-selected-snapshot").performScrollTo().assertTextContains("Ctx(n=12)", substring = true)
            assertEquals(GroupReplayFixture.Ctx(11), replay.snapshot(GroupReplayFixture.b).context)
            onNodeWithTag("panel-return").performClick()
            onNodeWithTag("panel-mode").assertTextContains("Replay")
            assertEquals(4, replay.position)
            onNodeWithTag("panel-details-toggle").performClick()
            onNodeWithTag("panel-selected-snapshot").performScrollTo().assertTextContains("Ctx(n=11)", substring = true)
        }

    @Test
    fun unboundedHost_wrapsContent_andEmptyHistoryStillShowsControls() =
        runComposeUiTest {
            val state = InspectorState(Inspector.of(emptyList()))
            setContent { MaterialTheme { Column(Modifier.width(320.dp)) { TimeTravelPanel(state) } } }
            onNodeWithTag("time-travel-panel").assertIsDisplayed()
            onNodeWithTag("panel-empty").assertTextContains("No recorded inputs", substring = true)
            onNodeWithTag("panel-machines").assertIsNotEnabled()
        }

    @Test
    fun narrowSidebar_usesVerticalHistory_andKeepsNavigationReachable() =
        runComposeUiTest {
            val replay = GroupReplayFixture.controls()
            val state = inspector(replay)
            setContent { MaterialTheme { TimeTravelPanel(state, groupReplay = replay, modifier = Modifier.width(240.dp).height(600.dp)) } }
            onNodeWithTag("panel-history-vertical").assertIsDisplayed()
            onNodeWithTag("panel-forward").assertIsDisplayed().performClick()
            onNodeWithTag("panel-position").assertTextContains("1 / 6")
            onNodeWithTag("panel-details-toggle").performClick()
            onNodeWithTag("panel-back").assertIsDisplayed().performClick()
            onNodeWithTag("panel-position").assertTextContains("0 / 6")
        }
}
