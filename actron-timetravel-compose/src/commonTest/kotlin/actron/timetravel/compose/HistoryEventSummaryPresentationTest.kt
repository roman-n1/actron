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
        val summary = summarize(details)
        assertEquals("Search", summary.action)
        assertEquals("Idle", summary.before)
        assertEquals("Loading", summary.after)
        assertEquals(HistoryChanges(fields = listOf("query", "result"), remaining = 1), summary.changes)
        assertFalse(
            summary.changes.fields
                .joinToString()
                .contains("unchanged"),
        )
        assertFalse(
            summary.changes.fields
                .joinToString()
                .contains("revision"),
        )
    }

    @Test
    fun unchangedEventsExplainTheEmptyPreview() {
        val fields = listOf(StateField("active", "Idle"), StateField("query", "Paris"))
        val summary = summarize(HistoryDetails("Refresh", "Action", "Unchanged", fields, fields))
        assertEquals("Refresh", summary.action)
        assertEquals(HistoryChanges(fields = emptyList(), remaining = 0), summary.changes)
    }

    @Test
    fun metadataOnlyChangesExplainTheEmptyPreview() {
        val summary =
            summarize(
                HistoryDetails("Start", "Runtime", "Handled", listOf(StateField("active", "Idle"), StateField("revision", "1")), listOf(StateField("active", "Loading"), StateField("revision", "2"))),
            )
        assertEquals(HistoryChanges(fields = emptyList(), remaining = 0), summary.changes)
    }

    @Test
    fun longFieldNamesAreBoundedAndNestedTypesStayVisible() {
        val path = "nested." + "a".repeat(80)
        val summary = summarize(HistoryDetails("Load", "Action", "Handled", emptyList(), listOf(StateField(path, "secret-value"), StateField("phase.type", "Loaded"))))
        assertEquals(HistoryChanges(fields = listOf("nested.aaaaaaaaaaaaaaaaaaaa…", "phase.type"), remaining = 0), summary.changes)
        assertFalse(
            summary.changes.fields
                .joinToString()
                .contains("secret-value"),
        )
    }

    @Test
    fun russianSummaryNamesChangesAndExplainsMetadataOnlyEvents() {
        val details = HistoryDetails("Load", "Action", "Handled", emptyList(), listOf(StateField("query", "Oslo"), StateField("phase.type", "Loading"), StateField("count", "12")))
        assertEquals(HistoryChanges(fields = listOf("query", "phase.type"), remaining = 1), summarize(details, "ru-RU").changes)
        assertEquals(HistoryChanges(fields = emptyList(), remaining = 0), summarize(details.copy(after = listOf(StateField("revision", "2"))), "ru").changes)
    }
}

private fun summarize(
    details: actron.timetravel.HistoryDetails,
    language: String = "en",
): HistoryEventSummary = historyEventSummary(details = details, unchanged = summaryCopy(language = language).getValue(key = HistoryText.NoStateChange))
