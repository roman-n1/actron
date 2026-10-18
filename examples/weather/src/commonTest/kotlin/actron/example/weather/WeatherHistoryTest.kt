@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.example.weather

import actron.core.InputAttribution
import actron.example.weather.core.debug.WeatherHistory
import actron.example.weather.feature.cities.presentation.CitySearchAction
import actron.example.weather.feature.cities.presentation.CitySearchMachine
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.statechart.machine.*
import kotlin.test.*

class WeatherHistoryTest {
    @Test
    fun realStoreRecordsBeforePanelOpensAndRetainsClosedFeature() =
        kotlinx.coroutines.test.runTest {
            val history = WeatherHistory()
            val instance =
                actron.example.weather.core.component.MachineInstance(
                    CitySearchMachine.create(),
                    CitySearchState(),
                    actron.example.weather.feature.cities.presentation.citySearchHandler(
                        actron.example.weather.feature.cities.domain.SearchCitiesUseCase(
                            actron.example.weather.feature.cities.domain
                                .CitySearchRepository { emptyList() },
                        ),
                    ),
                    coroutineContext,
                    history,
                )
            testScheduler.runCurrent()
            assertEquals(1, history.entries.value.size)
            for (query in listOf("P", "Pa", "Paris")) {
                instance.store.dispatch(CitySearchAction.ChangeQuery(query))
                testScheduler.runCurrent()
            }
            assertEquals(4, history.entries.value.size)
            testScheduler.advanceTimeBy(350)
            testScheduler.runCurrent()
            assertTrue(history.entries.value.any { it.details().contains("Found") })
            val beforeClose = history.entries.value.toList()
            instance.onDestroy()
            testScheduler.runCurrent()
            assertTrue(history.entries.value.containsAll(beforeClose))
            history.panelVisible.value = true
            assertTrue(history.entries.value.containsAll(beforeClose))
        }

    @Test
    fun hiddenHistoryRetainsEveryEditAndEvictsOldestAcrossInstances() {
        val history = WeatherHistory(capacity = 3)
        traceEdits(history, listOf("P", "Pa", "Par", "Paris"))
        assertFalse(history.panelVisible.value)
        val beforeOpening = history.entries.value
        assertEquals(3, beforeOpening.size)
        assertTrue(beforeOpening.last().details().contains("Paris"))
        assertTrue(beforeOpening.last().details().contains("Before:"))
        history.panelVisible.value = true
        assertEquals(beforeOpening, history.entries.value)
        traceEdits(history, listOf("Berlin"))
        assertEquals(3, history.entries.value.size)
        assertEquals(
            2,
            history.entries.value
                .map { it.machine }
                .distinct()
                .size,
        )
        assertTrue(
            history.entries.value
                .first()
                .id > beforeOpening.first().id,
        )
        val lastId =
            history.entries.value
                .last()
                .id
        history.clear()
        assertTrue(history.entries.value.isEmpty())
        traceEdits(history, listOf("Rome"))
        assertTrue(
            history.entries.value
                .first()
                .id > lastId,
        )
    }

    private fun traceEdits(
        history: WeatherHistory,
        queries: List<String>,
    ) {
        val machine = CitySearchMachine.create()
        val observer = history.observer(machine, CitySearchState())
        var snapshot = machine.initialSnapshot(CitySearchState())
        val inputs =
            listOf<MachineInput<CitySearchAction>>(MachineInput.Start(MachineTime.Zero)) +
                queries.map { MachineInput.Dispatch(CitySearchAction.ChangeQuery(it), MachineTime.Zero) }
        for (input in inputs) {
            val explained = machine.decideExplained(snapshot, input)
            observer.onDecided(InputAttribution.Unattributed, input, explained)
            snapshot = explained.decision.snapshot
        }
    }

    @Test
    fun bufferRequiresPositiveCapacity() {
        assertFailsWith<IllegalArgumentException> { WeatherHistory(0) }
    }
}
