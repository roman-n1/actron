package actron.timetravel.compose

import actron.timetravel.*
import kotlin.test.*

class HistoryInspectionTest {
    private fun details(
        before: String,
        after: String,
    ) = HistoryDetails(
        "Loaded(city=Paris)",
        "Effect",
        "Handled",
        listOf(StateField("query", before)),
        listOf(StateField("query", after)),
    )

    @Test
    fun searchCombinesTermsAndMatchesExactIdsAndChangedValues() {
        val timeline = HistoryTimeline<String>()
        val machine = timeline.machine("Search")
        repeat(11) { timeline.append(machine, 0, details("London", "Paris"), "snapshot") }
        val first = timeline.entries.value.first()
        assertTrue(historyMatches(first, "  SEARCH paris effect query "))
        assertTrue(historyMatches(first, "#1 handled london"))
        assertFalse(historyMatches(timeline.entries.value.last(), "#1"))
        assertFalse(historyMatches(first, "Paris Unhandled"))
        assertTrue(historyMatches(first, "   "))
    }

    @Test
    fun recordedChangesDoNotInventChangesAcrossEvictionGaps() {
        val timeline = HistoryTimeline<String>(1)
        val machine = timeline.machine("Search")
        timeline.append(machine, 0, details("London", "Paris"), "first")
        timeline.append(machine, 0, details("Paris", "Paris"), "unchanged")
        assertFalse(historyFieldChanges(timeline.entries.value.single(), "query"))
        timeline.append(machine, 0, details("Paris", "Oslo"), "changed")
        assertTrue(historyFieldChanges(timeline.entries.value.single(), "query"))
        assertFalse(historyFieldChanges(timeline.entries.value.single(), "que"))
    }

    @Test
    fun navigationRestoresContextAndSkipsEvictedLocations() {
        val timeline = HistoryTimeline<String>(3)
        val machine = timeline.machine("Search")
        repeat(3) { timeline.append(machine, 0, details("", "Paris"), "$it") }
        val state = HistoryPanelState<String>()
        val inspection = state.inspection
        state.select(timeline.entries.value[0])
        inspection.graph = true
        inspection.query = "Paris"
        inspection.focus = HistoryFieldFocus.Path("query")
        inspection.fieldQuery = "que"
        inspection.fullState = true
        state.select(timeline.entries.value[1])
        inspection.graph = false
        inspection.query = "#2"
        inspection.fieldQuery = "other"
        inspection.fullState = false
        state.select(timeline.entries.value[2])
        state.navigate(timeline.entries.value, true)
        assertEquals(2L, state.selection.single().id)
        assertEquals("#2", inspection.query)
        state.navigate(timeline.entries.value, true)
        assertEquals(1L, state.selection.single().id)
        assertTrue(inspection.graph)
        assertEquals("Paris", inspection.query)
        assertEquals(HistoryFieldFocus.Path("query"), inspection.focus)
        assertEquals("que", inspection.fieldQuery)
        assertTrue(inspection.fullState)
        state.navigate(timeline.entries.value, false)
        assertEquals(2L, state.selection.single().id)
        inspection.toggleBookmark(1)
        inspection.toggleBookmark(2)
        timeline.append(machine, 0, details("Paris", "Oslo"), "new")
        inspection.retain(
            timeline.entries.value
                .map { it.id }
                .toSet(),
        )
        assertEquals(setOf(2L), inspection.bookmarks)
        state.navigate(timeline.entries.value, true)
        assertEquals(2L, state.selection.single().id)
        assertTrue(inspection.back.isEmpty())
        state.select(timeline.entries.value.last())
        assertTrue(inspection.forward.isEmpty())
    }

    @Test
    fun comparisonRetainsAddedRemovedAndUnchangedFieldsWithoutSnapshots() {
        val before = listOf(StateField("removed", "old"), StateField("same", "value"))
        val after = listOf(StateField("added", "new"), StateField("same", "value"))
        assertEquals(listOf(StateChange("removed", "old", "<absent>"), StateChange("added", "<absent>", "new")), historyChanges(before, after, false))
        assertEquals(3, historyChanges(before, after, true).size)
    }
}
