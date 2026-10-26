package actron.example.weather

import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.weather.domain.HourlyForecast
import actron.example.weather.feature.weather.presentation.*
import actron.statechart.test.test
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class WeatherScenarioTest {
    @Test
    fun parallelForecastCompletesWhileCurrentRequestTimesOut() =
        runTest {
            val city = demoCities.first()
            val report =
                CityWeatherMachine.create().test(this, WeatherContext(city)) {
                    dispatch(WeatherAction.Refresh)
                    expectState<RequestState.Loading> { it.current }
                    expectState<RequestState.Loading> { it.hourly }
                    assertEquals(2, coverage.transitions.covered.size)
                    completeCommand(matching = { it is WeatherCommand.Hourly }) {
                        WeatherAction.ForecastLoaded(HourlyForecast(city, emptyList()))
                    }
                    expectState<RequestState.Success<HourlyForecast>> { it.hourly }
                    expectState<RequestState.Loading> { it.current }
                    advanceTimeBy(8.seconds)
                    expectState<RequestState.Error> { it.current }
                    expectState<RequestState.Success<HourlyForecast>> { it.hourly }
                }
            assertEquals(4, report.transitions.covered.size)
        }
}
