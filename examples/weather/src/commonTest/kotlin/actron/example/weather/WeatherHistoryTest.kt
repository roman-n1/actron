@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.example.weather

import actron.core.InputAttribution
import actron.example.weather.core.component.MachineInstance
import actron.example.weather.core.debug.WeatherHistory
import actron.example.weather.core.debug.continuation
import actron.example.weather.core.debug.retainedWeatherHistory
import actron.example.weather.core.session.WeatherFrame
import actron.example.weather.core.session.WeatherSavedState
import actron.example.weather.core.session.WeatherSavedStateCodec
import actron.example.weather.feature.cities.presentation.CitySearchAction
import actron.example.weather.feature.cities.presentation.CitySearchMachine
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.example.weather.feature.cities.presentation.SearchCitiesCommand
import actron.statechart.machine.*
import actron.timetravel.HistoryConfiguration
import actron.timetravel.compose.HistoryContinuation
import actron.timetravel.historyFields
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class WeatherHistoryTest {
    @Test
    fun debugExecutorCapturesGraphProfilesAndKeepsOldFramesFrozen() =
        runTest {
            val history = WeatherHistory()
            val instance =
                MachineInstance(
                    CitySearchMachine.create(),
                    CitySearchState(),
                    CommandHandler<SearchCitiesCommand, CitySearchAction> { _, _ -> },
                    coroutineContext,
                    history,
                    checkpoint = history.searchCheckpoint(),
                    update = history::updateSearch,
                )
            try {
                testScheduler.runCurrent()
                instance.store.dispatch(CitySearchAction.ChangeQuery("Pa"))
                testScheduler.runCurrent()
                // Frames contain completed measurements before the current event.
                instance.store.dispatch(CitySearchAction.ChangeQuery("Paris"))
                testScheduler.runCurrent()
                val selected =
                    history.timeline.entries.value
                        .last()
                val profile = selected.snapshot.profiles.getValue(DefinitionId("city-search"))
                assertTrue(profile.transitions.values.sumOf { it.hits } > 0)
                instance.store.dispatch(CitySearchAction.ChangeQuery("Berlin"))
                testScheduler.runCurrent()
                assertEquals(profile, selected.snapshot.profiles.getValue(DefinitionId("city-search")))
                assertTrue(
                    history.frame.profiles
                        .getValue(DefinitionId("city-search"))
                        .transitions.values
                        .sumOf { it.hits } > profile.transitions.values.sumOf { it.hits },
                )
            } finally {
                instance.onDestroy()
            }
        }

    @Test
    fun startupLimitBoundsHistoryAndIsAppliedAfterProcessRestore() {
        val configuration = HistoryConfiguration(capacity = 3)
        val state = StateKeeperDispatcher()
        val context = DefaultComponentContext(LifecycleRegistry(), stateKeeper = state)
        val history = retainedWeatherHistory(context, configuration)
        traceEdits(history, listOf("P", "Pa", "Par", "Paris"))
        assertEquals(3, history.timeline.entries.value.size)
        assertEquals("Paris", history.frame.search.snapshot.context.query)
        assertSame(history, retainedWeatherHistory(context, configuration))
        state.register("weather-checkpoint", WeatherSavedState.serializer()) { WeatherSavedStateCodec.encode(history) }
        val restored = retainedWeatherHistory(DefaultComponentContext(LifecycleRegistry(), stateKeeper = StateKeeperDispatcher(state.save())), configuration)
        assertNotSame(history, restored)
        assertEquals(3, restored.capacity)
        assertEquals("Paris", restored.frame.search.snapshot.context.query)
        assertTrue(
            restored.timeline.entries.value
                .isEmpty(),
        )
        traceEdits(restored, listOf("B", "Be", "Ber", "Berlin"))
        assertEquals(3, restored.timeline.entries.value.size)
    }

    @Test
    fun continuationExplainsExecutorRestrictionsInsteadOfGuessingFromState() {
        val initial = WeatherFrame()
        assertIs<HistoryContinuation.Available>(initial.continuation(false))
        val machine = CitySearchMachine.create()
        val started = machine.decide(initial.search.snapshot, MachineInput.Start(MachineTime.Zero))
        val editing = machine.decide(started.snapshot, MachineInput.Dispatch(CitySearchAction.ChangeQuery("Paris"), MachineTime.Zero))
        val timer = editing.timersScheduled.single()
        val loading = machine.decide(editing.snapshot, MachineInput.TimerFired(timer.id, timer.deadline))
        val ending = initial.copy(search = ExecutorCheckpoint(loading.snapshot, timer.deadline, ending = loading.commands.associateBy { it.id }))
        assertFalse(ending.canResume())
        val blocked = assertIs<HistoryContinuation.Unavailable>(ending.continuation(false))
        assertTrue(blocked.reason.contains("Command completions"))
        assertFalse(blocked.reason.contains("Effects"))
    }

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
