package actron.example.weather

import actron.core.InputAttribution
import actron.example.weather.app.debug.WeatherDisplay
import actron.example.weather.app.debug.WeatherHistory
import actron.example.weather.app.debug.retainedWeatherHistory
import actron.example.weather.feature.cities.data.LocalCitySearchRepository
import actron.example.weather.feature.cities.presentation.*
import actron.statechart.machine.*
import actron.timetravel.HistoryConfiguration
import actron.timetravel.historyFields
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeperDispatcher
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import kotlinx.serialization.json.Json
import kotlin.test.*

class WeatherHistoryTest {
    @Test
    fun configuredHistoryIsRetainedByDecomposeAndBounded() {
        val keeper = InstanceKeeperDispatcher()
        val configuration = HistoryConfiguration(capacity = 3)
        val history = retainedWeatherHistory(DefaultComponentContext(LifecycleRegistry(), instanceKeeper = keeper), configuration)
        traceEdits(history, listOf("P", "Pa", "Par", "Paris"))
        assertEquals(3, history.timeline.entries.value.size)
        assertSame(history, retainedWeatherHistory(DefaultComponentContext(LifecycleRegistry(), instanceKeeper = keeper), configuration))
        val fresh = retainedWeatherHistory(DefaultComponentContext(LifecycleRegistry()), configuration)
        assertEquals(3, fresh.capacity)
        assertEquals(emptyList(), fresh.timeline.entries.value)
        keeper.destroy()
    }

    @Test
    fun hiddenHistoryRetainsEditsAndPinsSelectionAfterEviction() {
        val history = WeatherHistory(3)
        traceEdits(history, listOf("P", "Pa"))
        assertFalse(history.panelVisible.value)
        val selected =
            history.timeline.entries.value
                .last { it.details.changes.any { change -> change.path == "query" } }
        history.panel.select(selected)
        history.panel.previewSelected()
        traceEdits(history, listOf("Berlin", "Berlinn"))
        assertEquals(3, history.timeline.entries.value.size)
        assertEquals(selected, history.panel.preview.single())
        assertEquals("Pa", assertIs<WeatherDisplay.Search>(selected.snapshot.screen).state.query)
        assertTrue(selected.details.changes.any { it.path == "query" })
        history.timeline.clear()
        assertEquals(emptyList(), history.timeline.entries.value)
        assertEquals(selected, history.panel.preview.single())
    }

    @Test
    fun coverageAccumulatesAcrossInstancesWithoutChangingHistoricalFrames() {
        val history = WeatherHistory(2)
        traceEdits(history, listOf("L", "Lo", "Lon"))
        val selected =
            history.timeline.entries.value
                .first()
        val selectedGraph = history.graph(selected) as actron.timetravel.compose.HistoryGraph.Machine
        assertEquals(actron.timetravel.compose.HistoryGraphProfile.Unavailable, selectedGraph.profile)
        val selectedHits = (selectedGraph.coverage as actron.timetravel.compose.HistoryGraphCoverage.Recorded).hits
        assertEquals(4L, selectedHits.values.sum())
        assertEquals(
            6L,
            history.timeline.entries.value
                .last()
                .snapshot.coverage.values
                .single()
                .hits.values
                .sum(),
        )
        traceEdits(history, listOf("Paris"))
        assertEquals(
            8L,
            history.timeline.entries.value
                .last()
                .snapshot.coverage.values
                .single()
                .hits.values
                .sum(),
        )
        assertEquals(4L, selectedHits.values.sum(), "Historical coverage is immutable")
        assertEquals(4L, (history.graph(selected) as actron.timetravel.compose.HistoryGraph.Machine).let { (it.coverage as actron.timetravel.compose.HistoryGraphCoverage.Recorded).hits.values.sum() })
    }

    private fun traceEdits(
        history: WeatherHistory,
        queries: List<String>,
    ) {
        val machine = CitySearchMachine.create(LocalCitySearchRepository)
        val checkpoint: ExecutorCheckpoint<CitySearchState, Unit> = ExecutorCheckpoint.initial(machine.initialSnapshot(CitySearchState.Idle()))
        val observer =
            history.observer(
                machine,
                checkpoint,
                { historyFields(Json.encodeToJsonElement(CitySearchState.serializer(), it)) },
                { WeatherDisplay.Search(it) },
            )
        var snapshot = checkpoint.snapshot
        val inputs = listOf<MachineInput<CitySearchAction>>(MachineInput.Start(MachineTime.Zero)) + queries.map { MachineInput.Dispatch(CitySearchAction.ChangeQuery(it), MachineTime.Zero) }
        for (input in inputs) {
            val explained = machine.decideExplained(snapshot, input)
            observer.onDecided(InputAttribution.Unattributed, input, explained)
            observer.onCommitted(InputAttribution.Unattributed, input, explained.decision)
            snapshot = explained.decision.snapshot
        }
    }
}
