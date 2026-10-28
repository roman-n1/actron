package actron.timetravel.compose

import actron.timetravel.HistoryDetails
import actron.timetravel.HistoryTimeline
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class HistoryEventCounterTest {
    @Test
    fun counterFollowsSelectionNavigationAndReturnToLive() =
        runComposeUiTest {
            val timeline = timeline()
            val state = HistoryPanelState<String>()
            setContent { MaterialTheme { HistoryPanel(timeline, state, {}, Modifier.size(1000.dp, 900.dp)) } }
            onNodeWithTag("history-event-counter", useUnmergedTree = true).assertTextEquals(liveCounter(3))
            runOnIdle { state.select(timeline.entries.value[1]) }
            onNodeWithTag("history-event-counter", useUnmergedTree = true).assertTextEquals(counter("2 / 3"))
            onNodeWithTag("history-previous").performClick()
            onNodeWithTag("history-event-counter", useUnmergedTree = true).assertTextEquals(counter("1 / 3"))
            onNodeWithTag("history-next").performClick()
            onNodeWithTag("history-event-counter", useUnmergedTree = true).assertTextEquals(counter("2 / 3"))
            onNodeWithTag("history-live").performClick()
            onNodeWithTag("history-event-counter", useUnmergedTree = true).assertTextEquals(liveCounter(3))
        }

    @Test
    fun filtersDoNotChangeTheSelectedOrLatestEventNumbers() =
        runComposeUiTest {
            val timeline = timeline()
            val state = HistoryPanelState<String>()
            setContent { MaterialTheme { HistoryPanel(timeline, state, {}, Modifier.size(1000.dp, 900.dp)) } }
            runOnIdle { state.select(timeline.entries.value[1]) }
            onNodeWithTag("history-search").performTextInput("#2")
            onNodeWithTag("history-event-counter", useUnmergedTree = true).assertTextEquals(counter("2 / 3"))
            runOnIdle { state.select(timeline.entries.value[0]) }
            onNodeWithTag("history-event-counter", useUnmergedTree = true).assertTextEquals(counter("1 / 3"))
            onNodeWithTag("history-live").performClick()
            onNodeWithTag("history-event-counter", useUnmergedTree = true).assertTextEquals(liveCounter(3))
        }

    @Test
    fun evictionDoesNotRenumberTheSelectedSnapshotOrTheTotal() =
        runComposeUiTest {
            val timeline = timeline()
            val state = HistoryPanelState<String>()
            state.select(timeline.entries.value[1])
            setContent { MaterialTheme { HistoryPanel(timeline, state, {}, Modifier.size(1000.dp, 900.dp)) } }
            runOnIdle { append(timeline) }
            onNodeWithTag("history-event-counter", useUnmergedTree = true).assertTextEquals(counter("2 / 4"))
            runOnIdle { append(timeline) }
            onNodeWithTag("history-event-counter", useUnmergedTree = true).assertTextEquals(counter("2 / 5"))
            onNodeWithTag("history-live").performClick()
            onNodeWithTag("history-event-counter", useUnmergedTree = true).assertTextEquals(liveCounter(5))
        }

    @Test
    fun collapsedNarrowListShowsTheSameCounterAndLimit() =
        runComposeUiTest {
            val timeline = timeline()
            val state = HistoryPanelState<String>()
            setContent { MaterialTheme { HistoryPanel(timeline, state, {}, Modifier.size(400.dp, 900.dp)) } }
            onNodeWithTag("history-entry-1").performClick()
            onNodeWithTag("history-collapsed-event-counter", useUnmergedTree = true).assertTextEquals(counter("1 / 3"))
            onNodeWithTag("history-next").performClick()
            onNodeWithTag("history-collapsed-event-counter", useUnmergedTree = true).assertTextEquals(counter("2 / 3"))
        }

    @Test
    fun continuousNumbersExceedCapacityInHistoryAndLive() =
        runComposeUiTest {
            val timeline = HistoryTimeline<String>(500)
            val machine = timeline.machine("Search")
            repeat(1000) { timeline.append(machine, 0, HistoryDetails("Refresh", "Action", "Handled", emptyList(), emptyList()), "snapshot") }
            val state = HistoryPanelState<String>()
            setContent { MaterialTheme { HistoryPanel(timeline, state, {}, Modifier.size(1000.dp, 900.dp)) } }
            onNodeWithTag("history-event-counter", useUnmergedTree = true).assertTextEquals(liveCounter(1000, 500))
            runOnIdle { state.select(timeline.entries.value[498]) }
            onNodeWithTag("history-event-counter", useUnmergedTree = true).assertTextEquals(counter("999 / 1000", 500))
            onNodeWithTag("history-live").performClick()
            onNodeWithTag("history-event-counter", useUnmergedTree = true).assertTextEquals(liveCounter(1000, 500))
        }

    @Test
    fun emptyLiveHistoryShowsZeroEvents() =
        runComposeUiTest {
            setContent { MaterialTheme { HistoryPanel(HistoryTimeline<String>(3), HistoryPanelState(), {}, Modifier.size(1000.dp, 900.dp)) } }
            onNodeWithTag("history-event-counter", useUnmergedTree = true).assertTextEquals(liveCounter(0))
        }

    private fun counter(
        position: String,
        capacity: Int = 3,
    ): String {
        val language: String = Locale.getDefault().language
        return "${expectedHistoryText(text = HistoryText.Event, language = language)} $position · ${expectedHistoryText(text = HistoryText.Capacity, language = language)} $capacity"
    }

    private fun liveCounter(
        latest: Int,
        capacity: Int = 3,
    ): String {
        val language: String = Locale.getDefault().language
        val label = if (language == "ru") "В истории:" else "In history:"
        return "$label $latest ${expectedHistoryText(text = HistoryText.Events, language = language)} · ${expectedHistoryText(text = HistoryText.Capacity, language = language)} $capacity"
    }

    private fun timeline(): HistoryTimeline<String> = HistoryTimeline<String>(3).also { timeline -> repeat(3) { append(timeline) } }

    private fun append(timeline: HistoryTimeline<String>) {
        timeline.append(timeline.machine("Search"), 0, HistoryDetails("Refresh", "Action", "Handled", emptyList(), emptyList()), "snapshot")
    }
}
