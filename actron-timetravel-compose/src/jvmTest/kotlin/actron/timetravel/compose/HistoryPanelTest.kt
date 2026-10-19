package actron.timetravel.compose

import actron.timetravel.HistoryDetails
import actron.timetravel.HistoryTimeline
import actron.timetravel.StateField
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class HistoryPanelTest {
    @Test
    fun actionPayloadPreviewAndContinueAreExplicit() =
        runComposeUiTest {
            val timeline = HistoryTimeline<String>()
            val machine = timeline.machine("Search")
            val details =
                HistoryDetails(
                    "QueryChanged(query=Paris)",
                    "Action",
                    "Handled",
                    listOf(StateField("active", "Idle"), StateField("query", "")),
                    listOf(StateField("active", "Debouncing"), StateField("query", "Paris")),
                )
            timeline.append(machine, 0, details, "Paris")
            timeline.append(timeline.machine("Weather"), 0, details.copy(input = "Refresh"), "Weather snapshot")
            timeline.append(timeline.machine("Navigation", actron.timetravel.HistorySourceKind.Navigation), 0, details.copy(input = "Back"), "Search")
            val state = HistoryPanelState<String>()
            val continued = mutableListOf<String>()
            setContent {
                MaterialTheme {
                    HistoryPanel(timeline, state, {
                        continued += it.snapshot
                        timeline.append(timeline.machine("Restored search", stableId = "Search"), 1, details, "Resumed")
                    }, Modifier.fillMaxSize())
                }
            }
            onNodeWithTag("history-machine-filter").performClick()
            onNodeWithText("Navigation").assertDoesNotExist()
            onNodeWithText("Search").performClick()
            onNodeWithTag("history-entry-2").assertDoesNotExist()
            onNodeWithTag("history-entry-1").performClick()
            onNodeWithTag("history-selected-input").assertTextEquals("QueryChanged(query=Paris)")
            runOnIdle { assertEquals("Paris", state.preview.single().snapshot) }
            onNodeWithTag("history-mode-history").assertIsSelected()
            onNodeWithTag("history-live").performClick().assertIsSelected()
            runOnIdle {
                assertEquals(emptyList(), state.preview)
                assertEquals(3, timeline.entries.value.size)
            }
            onNodeWithTag("history-continue").assertIsNotEnabled()
            onNodeWithTag("history-mode-history").performClick().assertIsSelected()
            runOnIdle { assertEquals("Paris", state.preview.single().snapshot) }
            onNodeWithTag("history-transition").assertExists()
            onNodeWithTag("history-continue").assertIsEnabled().performClick()
            runOnIdle {
                assertEquals(listOf("Paris"), continued)
                assertEquals(listOf("Paris", "Resumed"), timeline.entries.value.map { it.snapshot })
                assertEquals(emptyList(), state.preview)
                assertEquals(emptyList(), state.selection)
            }
            onNodeWithTag("history-entry-4").assertExists()
        }

    @Test
    fun switchingMachineClearsForeignSelectionAndHistoryUsesFilteredEntries() =
        runComposeUiTest {
            val timeline = HistoryTimeline<String>()
            val details = HistoryDetails("Load", "Action", "Handled", emptyList(), emptyList())
            timeline.append(timeline.machine("Search"), 0, details, "Search snapshot")
            timeline.append(timeline.machine("Weather"), 0, details, "Weather snapshot")
            val state = HistoryPanelState<String>()
            setContent { MaterialTheme { HistoryPanel(timeline, state, {}, Modifier.fillMaxSize()) } }
            onNodeWithTag("history-entry-2").performClick()
            onNodeWithTag("history-machine-filter").performClick()
            onNodeWithText("Search").performClick()
            onNodeWithTag("history-selected-input").assertDoesNotExist()
            runOnIdle {
                assertEquals(emptyList(), state.selection)
                assertEquals(emptyList(), state.preview)
            }
            onNodeWithTag("history-mode-history").performClick()
            runOnIdle { assertEquals("Search snapshot", state.preview.single().snapshot) }
            onNodeWithTag("history-entry-2").assertDoesNotExist()
        }

    @Test
    fun filterGroupsLifetimesByStableIdRatherThanDisplayName() =
        runComposeUiTest {
            val timeline = HistoryTimeline<String>()
            val details = HistoryDetails("Load", "Action", "Handled", emptyList(), emptyList())
            timeline.append(timeline.machine("Weather", stableId = "weather"), 0, details, "old")
            timeline.append(timeline.machine("Renamed weather", stableId = "weather"), 1, details, "new")
            timeline.append(timeline.machine("Search", stableId = "search"), 1, details, "search")
            val state = HistoryPanelState<String>()
            setContent { MaterialTheme { HistoryPanel(timeline, state, {}, Modifier.fillMaxSize()) } }
            onNodeWithTag("history-machine-filter").performClick()
            onNodeWithText("Renamed weather").assertDoesNotExist()
            onNodeWithText("Weather", useUnmergedTree = true).performClick()
            onNodeWithTag("history-entry-1").assertExists()
            onNodeWithTag("history-entry-2").assertExists()
            onNodeWithTag("history-entry-3").assertDoesNotExist()
            onNodeWithTag("history-mode-history").performClick()
            runOnIdle { assertEquals("new", state.preview.single().snapshot) }
        }

    @Test
    fun longPayloadIsExpandableAndRawTypesRemainAvailable() =
        runComposeUiTest {
            val payload = "Loaded(values=" + "sample,".repeat(40) + ")"
            val timeline = HistoryTimeline<String>()
            val rawType = "actron.example.weather.RequestState.Success"
            val details = HistoryDetails(payload, "Action", "Handled", emptyList(), listOf(StateField("current.type", rawType)))
            timeline.append(timeline.machine("Weather"), 0, details, "snapshot")
            val state = HistoryPanelState<String>()
            setContent { MaterialTheme { HistoryPanel(timeline, state, {}, Modifier.fillMaxSize()) } }
            onNodeWithTag("history-entry-1").performClick()
            onNodeWithTag("history-selected-input").assertTextEquals("Loaded")
            onNodeWithText("RequestState.Success").assertExists()
            onNodeWithTag("history-action-payload").performClick()
            onNodeWithTag("history-selected-input").assertTextEquals(payload)
            onNodeWithTag("history-action-payload").performClick()
            onNodeWithTag("history-selected-input").assertTextEquals("Loaded")
            onNodeWithText(historyLabelFullState()).performClick()
            onNodeWithText(rawType).assertExists()
        }

    private fun historyLabelFullState(): String = HistoryText.FullState.forLanguage(Locale.getDefault().language)

    @Test
    fun timeUsesDeviceLocaleAndTimeZone() {
        val originalLocale: Locale = Locale.getDefault()
        val originalZone: TimeZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.US)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Novosibirsk"))
            val instant = Instant.parse("2026-10-18T13:01:02.345Z")
            val expected =
                DateFormat
                    .getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                    .format(Date(instant.toEpochMilliseconds())) + " · 345 ms"
            runComposeUiTest {
                setContent { Text(formatHistoryTime(instant)) }
                onNodeWithText(expected).assertExists()
            }
        } finally {
            Locale.setDefault(originalLocale)
            TimeZone.setDefault(originalZone)
        }
    }
}
