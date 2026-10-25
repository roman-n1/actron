package actron.example.weather

import actron.example.weather.core.component.MachineInstance
import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.example.weather.feature.weather.domain.WeatherRepository
import actron.example.weather.feature.weather.presentation.CityWeatherMachine
import actron.example.weather.feature.weather.presentation.WeatherAction
import actron.example.weather.feature.weather.presentation.WeatherContext
import actron.example.weather.feature.weather.presentation.cityWeatherHandler
import actron.statechart.machine.MachineStore
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MachineInstanceTest {
    @Test
    fun retainedOwnerRunsWithoutDiagnosticsAndCancelsBothRequestsOnDestroy() =
        runTest {
            var started = 0
            var cancelled = 0

            suspend fun request(): Nothing {
                started++
                try {
                    awaitCancellation()
                } finally {
                    cancelled++
                }
            }
            val instance =
                MachineInstance(coroutineContext) { scope ->
                    MachineStore(
                        CityWeatherMachine.create(),
                        WeatherContext(demoCities.first()),
                        cityWeatherHandler(WeatherRepository { request() }, HourlyForecastRepository { request() }),
                        scope,
                        coroutineContext = coroutineContext,
                    )
                }
            testScheduler.runCurrent()
            assertTrue(instance.store.state.value.isStarted)
            instance.store.dispatch(WeatherAction.Refresh)
            testScheduler.runCurrent()
            assertEquals(2, started)
            assertEquals(0, cancelled)
            instance.onDestroy()
            testScheduler.runCurrent()
            assertEquals(2, cancelled)
        }
}
