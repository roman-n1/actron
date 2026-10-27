package actron.example.weather

import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastAction
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastContext
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastMachine
import actron.example.weather.feature.weather.domain.HourlyForecast
import actron.statechart.test.test
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class WeatherScenarioTest {
    @Test
    fun forecastCanBeExecutedAndRetriedWithoutAnyScreenOrCurrentWeatherMachine() =
        runTest(testBody = {
            val city = demoCities.first()
            HourlyForecastMachine.create().test(scope = this, initial = HourlyForecastContext(city = city), scenario = {
                expectState<RequestState.Ready>(select = { it.state })
                dispatch(action = HourlyForecastAction.Refresh)
                completeCommand(result = { HourlyForecastAction.Loaded(forecast = HourlyForecast(city = city, hours = emptyList())) })
                expectState<RequestState.Success<HourlyForecast>>(select = { it.state })
                dispatch(action = HourlyForecastAction.Retry)
                advanceTimeBy(duration = 8.seconds)
                expectState<RequestState.Error>(select = { it.state })
            })
        })
}
