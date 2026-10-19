package actron.timetravel

import kotlinx.serialization.json.*
import kotlin.test.*

class HistoryTimelineTest {
    @Test
    fun truncationKeepsPastAndNewRestorationEventsWithoutReusingIds() {
        val history = HistoryTimeline<String>()
        val machine = history.machine("Search")
        val details = HistoryDetails("Input", "Action", "Handled", emptyList(), emptyList())
        var prefixDiscards = 0
        for (value in listOf("A", "B", "C", "D")) {
            history.append(machine, 0, details, value, discard = { prefixDiscards++ })
        }
        val selected = history.entries.value[1]
        val oldHead =
            history.entries.value
                .last()
                .id
        history.append(machine, 1, details, "Restored")
        history.truncateAfter(selected.id, oldHead)
        assertEquals(listOf("A", "B", "Restored"), history.entries.value.map { it.snapshot })
        assertEquals(0, prefixDiscards)
        history.append(machine, 1, details, "E")
        assertTrue(
            history.entries.value
                .last()
                .id > oldHead,
        )
    }

    @Test
    fun truncationSupportsPinnedSelectionAlreadyEvictedFromBuffer() {
        val history = HistoryTimeline<String>(2)
        val machine = history.machine("Search")
        val details = HistoryDetails("Input", "Action", "Handled", emptyList(), emptyList())
        history.append(machine, 0, details, "A")
        val pinned = history.entries.value.single()
        history.append(machine, 0, details, "B")
        history.append(machine, 0, details, "C")
        history.truncateAfter(pinned.id)
        assertTrue(history.entries.value.isEmpty())
        assertEquals("A", pinned.snapshot)
    }

    @Test
    fun lifetimesHaveUniqueIdsAndShareStableIdentity() {
        val history = HistoryTimeline<String>()
        val first = history.machine("Weather", stableId = "weather")
        val second = history.machine("Weather", stableId = "weather")
        assertEquals(1L, first.id)
        assertEquals(2L, second.id)
        assertEquals(first.stableId, second.stableId)
    }

    @Test
    fun diffDistinguishesAbsentFromEmptyAndNestedArrayChanges() {
        val details =
            HistoryDetails(
                "ChangeQuery",
                "Action",
                "Handled",
                historyFields(Json.parseToJsonElement("""{"query":"","items":[{"name":"A"}]}""")),
                historyFields(Json.parseToJsonElement("""{"query":"A","items":[],"new":""}""")),
            )
        assertTrue(details.changes.any { it.path == "items[0].name" && it.after == "<absent>" })
        assertTrue(details.changes.any { it.path == "new" && it.before == "<absent>" && it.after == "\"\"" })
        assertTrue(details.changes.any { it.path == "query" })
    }

    @Test
    fun globalCapacityDiscardsAcrossMachinesWithoutMutatingPinnedEntries() {
        val history = HistoryTimeline<String>(2)
        val search = history.machine("search")
        val weather = history.machine("weather")
        val details = HistoryDetails("Start", "Runtime", "Handled", emptyList(), emptyList())
        var discarded = 0
        history.append(search, 0, details, "one", discard = { discarded++ })
        val pinned = history.entries.value.single()
        history.append(weather, 0, details, "two", discard = { discarded++ })
        history.append(search, 1, details, "three", discard = { discarded++ })
        assertEquals(listOf("two", "three"), history.entries.value.map { it.snapshot })
        assertEquals("one", pinned.snapshot)
        assertEquals(1, discarded)
        history.clear()
        assertEquals(3, discarded)
    }
}
