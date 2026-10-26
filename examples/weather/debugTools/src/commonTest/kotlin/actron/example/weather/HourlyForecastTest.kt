@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.example.weather

import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.weather.data.OpenMeteoHourlyRepository
import actron.example.weather.feature.weather.domain.ForecastHour
import actron.example.weather.feature.weather.domain.HourlyForecast
import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.example.weather.feature.weather.domain.WeatherRepository
import actron.example.weather.feature.weather.presentation.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HourlyForecastTest {
    @Test
    fun hourlyRequest_isSeparateFromCurrentWeather_andUsesSelectedCoordinates() =
        runTest {
            val client =
                HttpClient(
                    MockEngine { request ->
                        assertEquals("38.72", request.url.parameters["latitude"])
                        assertEquals("-9.14", request.url.parameters["longitude"])
                        assertEquals("6", request.url.parameters["forecast_hours"])
                        assertEquals("auto", request.url.parameters["timezone"])
                        assertEquals("temperature_2m,wind_speed_10m,precipitation_probability", request.url.parameters["hourly"])
                        assertTrue("current" !in request.url.parameters.names())
                        respond("""{"hourly":{"time":["2026-10-13T23:00","2026-10-14T00:00"],"temperature_2m":[18,17],"wind_speed_10m":[12,13],"precipitation_probability":[10,20]}}""")
                    },
                )
            try {
                val result = OpenMeteoHourlyRepository(client).load(demoCities[1])
                assertEquals(demoCities[1], result.city)
                assertEquals(ForecastHour("2026-10-14T00:00", 17.0, 13.0, 20), result.hours.last())
            } finally {
                client.close()
            }
        }

    @Test
    fun incompleteHourlyResponse_isRejected() =
        runTest {
            val client = HttpClient(MockEngine { respond("""{"hourly":{"time":["12:00"],"temperature_2m":[],"wind_speed_10m":[12],"precipitation_probability":[10]}}""") })
            try {
                assertFailsWith<IllegalArgumentException> { OpenMeteoHourlyRepository(client).load(demoCities.first()) }
            } finally {
                client.close()
            }
        }

    @Test
    fun forecastCompletesWhileCurrentWeatherIsPending_andTimeoutIsIndependent() =
        runTest {
            var cancelled = false
            val store =
                weatherStore(
                    WeatherRepository {
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled = true
                        }
                    },
                    HourlyForecastRepository { city -> HourlyForecast(city, listOf(ForecastHour("12:00", 18.0, 10.0, 5))) },
                )
            try {
                store.start()
                store.dispatch(WeatherAction.Refresh)
                store.state.first { it.context.hourly is RequestState.Success }
                assertEquals(RequestState.Loading, store.state.value.context.current)
                store.state.first { it.context.current == RequestState.Error(cause = RequestFailure.Timeout) }
                testScheduler.runCurrent()
                assertTrue(cancelled)
                assertTrue(store.state.value.context.hourly is RequestState.Success)
            } finally {
                store.close()
            }
        }

    @Test
    fun forecastRetryDoesNotReloadCurrentWeather_andRefreshReloadsBoth() =
        runTest {
            var currentCalls = 0
            var forecastCalls = 0
            val store =
                weatherStore(
                    WeatherRepository { city ->
                        currentCalls++
                        actron.example.weather.feature.weather.domain
                            .WeatherReading(city, 20.0, 19.0, 2.0, 0, "12:00")
                    },
                    HourlyForecastRepository { city ->
                        if (++forecastCalls == 1) error("offline")
                        HourlyForecast(city, emptyList())
                    },
                )
            try {
                store.start()
                store.dispatch(WeatherAction.Refresh)
                store.state.first { it.context.hourly is RequestState.Error && it.context.current is RequestState.Success }
                store.dispatch(WeatherAction.RetryForecast)
                store.state.first { it.context.hourly is RequestState.Success }
                assertEquals(2, currentCalls)
                assertEquals(2, forecastCalls)
                store.dispatch(WeatherAction.Refresh)
                testScheduler.runCurrent()
                assertEquals(4, currentCalls)
                assertEquals(3, forecastCalls)
            } finally {
                store.close()
            }
        }
}

private fun kotlinx.coroutines.test.TestScope.weatherStore(
    current: WeatherRepository,
    hourly: HourlyForecastRepository,
) = actron.statechart.machine.MachineStore(
    CityWeatherMachine.create(),
    WeatherContext(demoCities.first()),
    cityWeatherHandler(current, hourly),
    backgroundScope,
    clock =
        actron.statechart.machine.MachineClock
            .monotonic(testScheduler.timeSource),
    coroutineContext = coroutineContext,
)
