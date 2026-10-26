package actron.timetravel.compose

import actron.timetravel.HistoryDetails
import actron.timetravel.StateField
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryTransitionPresentationTest {
    @Test
    fun parallelTransitionKeepsOnlyTheChangedRegion() {
        val details = details("Parallel, Current, Current.Loading, Hourly, Hourly.Success", "Parallel, Current, Current.Success, Hourly, Hourly.Success")
        val summary = historyEventSummary(details)
        assertEquals("Current.Loading", summary.before)
        assertEquals("Current.Success", summary.after)
        assertEquals("Parallel, Current, Current.Loading, Hourly, Hourly.Success", details.before.single().value)
    }

    @Test
    fun bothChangedRegionsKeepTheirNames() {
        val summary = historyEventSummary(details("Parallel, Current, Current.Loading, Hourly, Hourly.Loading", "Parallel, Current, Current.Success, Hourly, Hourly.Error"))
        assertEquals("Current.Loading, Hourly.Loading", summary.before)
        assertEquals("Current.Success, Hourly.Error", summary.after)
    }

    @Test
    fun unchangedConfigurationIsExplicitEvenWhenItsOrderChanges() {
        val summary = historyEventSummary(details("Parallel, Current, Current.Success", "Current.Success, Parallel, Current"))
        assertEquals("No state change", summary.before)
        assertEquals("No state change", summary.after)
    }

    @Test
    fun missingStatesAreNotReportedAsUnchanged() {
        val summary = historyEventSummary(details("", ""))
        assertEquals("—", summary.before)
        assertEquals("—", summary.after)
    }

    @Test
    fun enteringAndLeavingConfigurationsHaveAnEmptySide() {
        assertEquals("—", historyEventSummary(details("", "Loading")).before)
        assertEquals("Loading", historyEventSummary(details("", "Loading")).after)
        assertEquals("Loading", historyEventSummary(details("Loading", "")).before)
        assertEquals("—", historyEventSummary(details("Loading", "")).after)
    }

    @Test
    fun screenNamesRemainWholeAndUnchangedTextIsLocalized() {
        val details = HistoryDetails("Open", "Action", "Handled", listOf(StateField("screen", "Weather, today")), listOf(StateField("screen", "Search")))
        assertEquals("Weather, today", historyEventSummary(details).before)
        assertEquals("Search", historyEventSummary(details).after)
        assertEquals("Без смены состояния", historyEventSummary(details.copy(after = details.before), "ru").before)
    }

    private fun details(
        before: String,
        after: String,
    ): HistoryDetails = HistoryDetails("Loaded", "Action", "Handled", listOf(StateField("active", before)), listOf(StateField("active", after)))
}
