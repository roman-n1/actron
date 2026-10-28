package actron.timetravel.compose

import actron.timetravel.HistoryDetails
import actron.timetravel.StateField
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryTransitionPresentationTest {
    @Test
    fun parallelTransitionKeepsOnlyTheChangedRegion() {
        val details = details("Parallel, Current, Current.Loading, Hourly, Hourly.Success", "Parallel, Current, Current.Success, Hourly, Hourly.Success")
        val summary = summarize(details)
        assertEquals("Current.Loading", summary.before)
        assertEquals("Current.Success", summary.after)
        assertEquals("Parallel, Current, Current.Loading, Hourly, Hourly.Success", details.before.single().value)
    }

    @Test
    fun bothChangedRegionsKeepTheirNames() {
        val summary = summarize(details("Parallel, Current, Current.Loading, Hourly, Hourly.Loading", "Parallel, Current, Current.Success, Hourly, Hourly.Error"))
        assertEquals("Current.Loading, Hourly.Loading", summary.before)
        assertEquals("Current.Success, Hourly.Error", summary.after)
    }

    @Test
    fun unchangedConfigurationIsExplicitEvenWhenItsOrderChanges() {
        val summary = summarize(details("Parallel, Current, Current.Success", "Current.Success, Parallel, Current"))
        assertEquals("No state change", summary.before)
        assertEquals("No state change", summary.after)
    }

    @Test
    fun missingStatesAreNotReportedAsUnchanged() {
        val summary = summarize(details("", ""))
        assertEquals("—", summary.before)
        assertEquals("—", summary.after)
    }

    @Test
    fun enteringAndLeavingConfigurationsHaveAnEmptySide() {
        assertEquals("—", summarize(details("", "Loading")).before)
        assertEquals("Loading", summarize(details("", "Loading")).after)
        assertEquals("Loading", summarize(details("Loading", "")).before)
        assertEquals("—", summarize(details("Loading", "")).after)
    }

    @Test
    fun screenNamesRemainWholeAndUnchangedTextIsLocalized() {
        val details = HistoryDetails("Open", "Action", "Handled", listOf(StateField("screen", "Weather, today")), listOf(StateField("screen", "Search")))
        assertEquals("Weather, today", summarize(details).before)
        assertEquals("Search", summarize(details).after)
        assertEquals("Без смены состояния", summarize(details.copy(after = details.before), "ru").before)
    }

    private fun details(
        before: String,
        after: String,
    ): HistoryDetails = HistoryDetails("Loaded", "Action", "Handled", listOf(StateField("active", before)), listOf(StateField("active", after)))
}

private fun summarize(
    details: actron.timetravel.HistoryDetails,
    language: String = "en",
): HistoryEventSummary = historyEventSummary(details = details, unchanged = summaryCopy(language = language).getValue(key = HistoryText.NoStateChange))
