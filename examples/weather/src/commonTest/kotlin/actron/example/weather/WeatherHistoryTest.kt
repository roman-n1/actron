@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.example.weather

import actron.core.InputAttribution
import actron.example.weather.core.debug.*
import actron.example.weather.feature.cities.presentation.*
import actron.statechart.machine.*
import actron.timetravel.historyFields
import kotlinx.serialization.json.Json
import kotlin.test.*

class WeatherHistoryTest {
    @Test
    fun hiddenHistoryRetainsEditsAndPinsSelectionAfterEviction() {
        val history = WeatherHistory(3)
        traceEdits(history, listOf("P", "Pa"))
        assertFalse(history.panelVisible.value)
        val selected =
            history.timeline.entries.value
                .last()
        history.panel.select(selected)
        history.panel.previewSelected()
        traceEdits(history, listOf("Berlin", "Berlinn"))
        assertEquals(3, history.timeline.entries.value.size)
        assertEquals(selected, history.panel.preview.single())
        assertEquals("Pa", selected.snapshot.search.snapshot.context.query)
        assertTrue(selected.details.changes.any { it.path == "query" })
        history.timeline.clear()
        assertTrue(
            history.timeline.entries.value
                .isEmpty(),
        )
        assertEquals(selected, history.panel.preview.single())
    }

    @Test
    fun oldBranchCallbacksCannotChangeNewBranch() {
        val history = WeatherHistory()
        val machine = CitySearchMachine.create()
        val checkpoint: ExecutorCheckpoint<CitySearchState, SearchCitiesCommand> = ExecutorCheckpoint.initial(machine.initialSnapshot(CitySearchState()))
        val observer = history.observer(machine, checkpoint, { historyFields(Json.encodeToJsonElement(CitySearchState.serializer(), it)) }, history::updateSearch)
        history.beginBranch(WeatherFrame())
        observer.onDecided(InputAttribution.Unattributed, MachineInput.Start(MachineTime.Zero), machine.decideExplained(checkpoint.snapshot, MachineInput.Start(MachineTime.Zero)))
        assertEquals(0, history.timeline.entries.value.size)
        assertFalse(history.frame.search.snapshot.isStarted)
    }

    @Test
    fun coverageAccumulatesAcrossInstancesAndBranchesFromSelectedSnapshot() {
        val history = WeatherHistory(2)
        traceEdits(history, listOf("L", "Lo", "Lon"))
        val selected =
            history.timeline.entries.value
                .first()
        val selectedGraph = history.graph(selected) as actron.timetravel.compose.HistoryGraph.Machine
        val selectedHits = (selectedGraph.coverage as actron.timetravel.compose.HistoryGraphCoverage.Recorded).hits
        assertEquals(2L, selectedHits.values.sum())
        assertEquals(
            3L,
            history.frame.coverage.values
                .single()
                .hits.values
                .sum(),
        )
        history.beginBranch(selected.snapshot)
        traceEdits(history, listOf("Paris"))
        assertEquals(
            3L,
            history.frame.coverage.values
                .single()
                .hits.values
                .sum(),
        )
        assertEquals(2L, selectedHits.values.sum(), "Historical coverage is immutable")
        assertEquals(2L, (history.graph(selected) as actron.timetravel.compose.HistoryGraph.Machine).let { (it.coverage as actron.timetravel.compose.HistoryGraphCoverage.Recorded).hits.values.sum() })
    }

    private fun traceEdits(
        history: WeatherHistory,
        queries: List<String>,
    ) {
        val machine = CitySearchMachine.create()
        val checkpoint: ExecutorCheckpoint<CitySearchState, SearchCitiesCommand> = ExecutorCheckpoint.initial(machine.initialSnapshot(CitySearchState()))
        val observer = history.observer(machine, checkpoint, { historyFields(Json.encodeToJsonElement(CitySearchState.serializer(), it)) }, history::updateSearch)
        var snapshot = checkpoint.snapshot
        val inputs = listOf<MachineInput<CitySearchAction>>(MachineInput.Start(MachineTime.Zero)) + queries.map { MachineInput.Dispatch(CitySearchAction.ChangeQuery(it), MachineTime.Zero) }
        inputs.forEach { input ->
            val explained = machine.decideExplained(snapshot, input)
            observer.onDecided(InputAttribution.Unattributed, input, explained)
            snapshot = explained.decision.snapshot
        }
    }
}
