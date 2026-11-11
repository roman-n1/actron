@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.example.weather

import actron.example.weather.app.debug.WeatherHistory
import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.feature.cities.data.LocalCitySearchRepository
import actron.example.weather.feature.cities.presentation.*
import actron.example.weather.feature.cities.presentation.toSearchSelection
import actron.example.weather.feature.weather.domain.*
import actron.example.weather.feature.weather.presentation.*
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeperDispatcher
import com.arkivanov.essenty.instancekeeper.getOrCreate
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.statekeeper.SerializableContainer
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class WeatherProcessRestoreTest {
    @Test
    fun processRecreationKeepsRouteAndStartsFreshRequests() =
        runTest {
            var currentCalls = 0
            var forecastCalls = 0
            val current =
                WeatherRepository { city ->
                    currentCalls++
                    WeatherReading(city = city, temperature = 10.0, feelsLike = 9.0, wind = 1.0, code = 61, time = "saved")
                }
            val forecast =
                HourlyForecastRepository { city ->
                    forecastCalls++
                    HourlyForecast(city, listOf(ForecastHour("12:00", 10.0, 1.0, 0)))
                }
            val keeper = InstanceKeeperDispatcher()
            val lifecycle = LifecycleRegistry()
            val state = StateKeeperDispatcher()
            val root = weatherRootFixture(DefaultComponentContext(lifecycle, instanceKeeper = keeper, stateKeeper = state), current, LocalCitySearchRepository, forecast, uiContext = coroutineContext, machineContext = coroutineContext)
            lifecycle.resume()
            testScheduler.runCurrent()
            assertIs<CitySearchComponent>(
                root
                    .getChildStack()
                    .value.active.instance,
            ).onUiEvent(demoCities[0].toSearchSelection())
            testScheduler.runCurrent()
            val weather =
                assertIs<CityWeatherComponent>(
                    root
                        .getChildStack()
                        .value.active.instance,
                )
            assertTrue(
                weather.state.value.advice
                    .isNotEmpty(),
            )
            weather.onUiEvent(event = CityWeatherIntent.DismissAdvice)
            testScheduler.runCurrent()
            val expected = weather.state.value
            assertTrue(expected.advice.isEmpty())
            assertTrue(expected.adviceActions.isEmpty())
            val bytes = Json.encodeToString(SerializableContainer.serializer(), state.save())
            lifecycle.destroy()
            keeper.destroy()
            testScheduler.runCurrent()
            val newKeeper = InstanceKeeperDispatcher()
            val newLifecycle = LifecycleRegistry()
            val restored = weatherRootFixture(DefaultComponentContext(newLifecycle, instanceKeeper = newKeeper, stateKeeper = StateKeeperDispatcher(Json.decodeFromString(SerializableContainer.serializer(), bytes))), current, LocalCitySearchRepository, forecast, uiContext = coroutineContext, machineContext = coroutineContext)
            newLifecycle.resume()
            testScheduler.runCurrent()
            val restoredWeather =
                assertIs<CityWeatherComponent>(
                    restored
                        .getChildStack()
                        .value.active.instance,
                ).state.value
            assertEquals(expected.city, restoredWeather.city)
            assertTrue(restoredWeather.advice.isNotEmpty(), "A fresh weather screen reloads advice after process recreation")
            assertEquals(4, currentCalls)
            assertEquals(2, forecastCalls)
            newLifecycle.destroy()
            newKeeper.destroy()
        }
}
