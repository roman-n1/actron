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
        assertEquals("Changed fields: query, result · more: 1", summary.changes)
        assertFalse(summary.changes.contains("unchanged"))
        assertFalse(summary.changes.contains("revision"))
    }

    @Test
    fun unchangedEventsExplainTheEmptyPreview() {
        val fields = listOf(StateField("active", "Idle"), StateField("query", "Paris"))
        val summary = historyEventSummary(HistoryDetails("Refresh", "Action", "Unchanged", fields, fields))
        assertEquals("Refresh", summary.action)
        assertEquals("No other field changes", summary.changes)
    }

    @Test
    fun metadataOnlyChangesExplainTheEmptyPreview() {
        val summary =
            historyEventSummary(
                HistoryDetails("Start", "Runtime", "Handled", listOf(StateField("active", "Idle"), StateField("revision", "1")), listOf(StateField("active", "Loading"), StateField("revision", "2"))),
            )
        assertEquals("No other field changes", summary.changes)
    }

    @Test
    fun longFieldNamesAreBoundedAndNestedTypesStayVisible() {
        val path = "nested." + "a".repeat(80)
        val summary = historyEventSummary(HistoryDetails("Load", "Action", "Handled", emptyList(), listOf(StateField(path, "secret-value"), StateField("phase.type", "Loaded"))))
        assertEquals("Changed fields: nested.aaaaaaaaaaaaaaaaaaaa…, phase.type", summary.changes)
        assertFalse(summary.changes.contains("secret-value"))
    }

    @Test
    fun russianSummaryNamesChangesAndExplainsMetadataOnlyEvents() {
        val details = HistoryDetails("Load", "Action", "Handled", emptyList(), listOf(StateField("query", "Oslo"), StateField("phase.type", "Loading"), StateField("count", "12")))
        assertEquals("Изменены поля: query, phase.type · ещё 1", historyEventSummary(details, "ru-RU").changes)
        assertEquals("Других изменений полей нет", historyEventSummary(details.copy(after = listOf(StateField("revision", "2"))), "ru").changes)
    }
}
