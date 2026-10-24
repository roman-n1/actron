@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.example.weather

import actron.example.weather.core.component.MachineInstance
import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.core.session.WeatherSavedState
import actron.example.weather.core.session.WeatherSavedStateCodec
import actron.example.weather.core.session.WeatherSession
import actron.example.weather.feature.cities.domain.*
import actron.example.weather.feature.cities.presentation.*
import actron.statechart.machine.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class WeatherCheckpointPersistenceTest {
    private fun clock(scheduler: TestCoroutineScheduler) =
        object : MachineClock {
            override fun now() = MachineTime(scheduler.currentTime.milliseconds)

            override suspend fun delayUntil(deadline: MachineTime) {
                val remaining = deadline - now()
                if (remaining.isPositive()) delay(remaining)
            }
        }

    @Test
    fun debounceKeepsRemainingDelayAndCompletedSearchDoesNotReload() =
        runTest {
            val requests = mutableListOf<String>()
            val options = listOf(CityOption(demoCities[0], "saved region"))
            val repo =
                CitySearchRepository { query ->
                    requests += query
                    options
                }
            var history = WeatherSession(clockFactory = { clock(testScheduler) })

            fun instance() =
                MachineInstance(
                    CitySearchMachine.create(),
                    CitySearchState(),
                    citySearchHandler(SearchCitiesUseCase(repo)),
                    coroutineContext,
                    history,
                    checkpoint = history.searchCheckpoint(),
                    update = history::updateSearch,
                )
            var machine = instance()
            runCurrent()
            machine.store.dispatch(CitySearchAction.ChangeQuery("Paris"))
            runCurrent()
            advanceTimeBy(100)
            val saved = Json.decodeFromString(WeatherSavedState.serializer(), Json.encodeToString(WeatherSavedState.serializer(), WeatherSavedStateCodec.encode(history)))
            machine.onDestroy()
            runCurrent()
            advanceTimeBy(60_000)
            history = WeatherSavedStateCodec.decode(saved) { clock(testScheduler) }
            machine = instance()
            runCurrent()
            assertEquals(RequestState.Debouncing, machine.store.state.value.context.phase)
            advanceTimeBy(249)
            runCurrent()
            assertTrue(requests.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf("Paris"), requests)
            assertEquals(RequestState.Success(options), machine.store.state.value.context.phase)
            val completed = WeatherSavedStateCodec.encode(history)
            machine.onDestroy()
            runCurrent()
            history = WeatherSavedStateCodec.decode(completed) { clock(testScheduler) }
            machine = instance()
            runCurrent()
            assertEquals("Paris", machine.store.state.value.context.query)
            assertEquals(RequestState.Success(options), machine.store.state.value.context.phase)
            assertEquals(listOf("Paris"), requests)
            machine.onDestroy()
        }

    @Test
    fun runningSearchRestartsOnceAndAcceptsNewInput() =
        runTest {
            var calls = 0
            val repo =
                CitySearchRepository {
                    calls++
                    awaitCancellation()
                }
            var history = WeatherSession(clockFactory = { clock(testScheduler) })

            fun instance() =
                MachineInstance(
                    CitySearchMachine.create(),
                    CitySearchState(),
                    citySearchHandler(SearchCitiesUseCase(repo)),
                    coroutineContext,
                    history,
                    checkpoint = history.searchCheckpoint(),
                    update = history::updateSearch,
                )
            var machine = instance()
            runCurrent()
            machine.store.dispatch(CitySearchAction.ChangeQuery("Paris"))
            runCurrent()
            advanceTimeBy(350)
            runCurrent()
            assertEquals(1, calls)
            val saved = WeatherSavedStateCodec.encode(history)
            machine.onDestroy()
            runCurrent()
            history = WeatherSavedStateCodec.decode(saved) { clock(testScheduler) }
            machine = instance()
            runCurrent()
            assertEquals(2, calls)
            assertEquals(RequestState.Loading, machine.store.state.value.context.phase)
            machine.store.dispatch(CitySearchAction.ChangeQuery("Tokyo"))
            runCurrent()
            assertEquals("Tokyo", machine.store.state.value.context.query)
            assertEquals(RequestState.Debouncing, machine.store.state.value.context.phase)
            machine.onDestroy()
        }

    @Test
    fun rejectsUnsupportedOrCorruptCheckpoint() {
        val saved = WeatherSavedStateCodec.encode(WeatherSession())
        assertFailsWith<IllegalArgumentException> { WeatherSavedStateCodec.decode(saved.copy(format = 99)) }
        assertFailsWith<IllegalArgumentException> { WeatherSavedStateCodec.decode(saved.copy(checkpoints = "broken")) }
    }
}
