package actron.timetravel.compose

import actron.timetravel.HistoryDetails
import actron.timetravel.StateField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HistoryEventSummaryPresentationTest {
    @Test
    fun longChangesAreBoundedWithoutRepeatingUnchangedFieldsOrTraceMetadata() {
        val details =
            HistoryDetails(
                "sample.actions.Search(query=all-the-action-data)",
                "Action",
                "Handled",
                listOf(StateField("active", "Idle"), StateField("query", "Paris"), StateField("unchanged", "same"), StateField("revision", "1")),
                listOf(StateField("active", "Loading"), StateField("query", "Oslo"), StateField("unchanged", "same"), StateField("revision", "2"), StateField("result", "a".repeat(80)), StateField("count", "12")),
            )
        val summary = historyEventSummary(details)
        assertEquals("Search", summary.action)
        assertEquals("Idle", summary.before)
        assertEquals("Loading", summary.after)
        assertEquals("query: Paris → Oslo\nresult: <absent> → aaaaaaaaaaaaaaaaaaaaaaaaaaa…", summary.changes)
        assertEquals(1, summary.moreChanges)
        assertFalse(summary.changes.contains("unchanged"))
        assertFalse(summary.changes.contains("revision"))
    }

    @Test
    fun unchangedEventsHaveNoFieldPreview() {
        val fields = listOf(StateField("active", "Idle"), StateField("query", "Paris"))
        val summary = historyEventSummary(HistoryDetails("Refresh", "Action", "Unchanged", fields, fields))
        assertEquals("Refresh", summary.action)
        assertEquals("", summary.changes)
        assertEquals(0, summary.moreChanges)
    }
}
