@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.example.weather

import actron.decompose.session.SessionContinuation
import actron.example.weather.app.debug.WeatherDisplay
import actron.example.weather.app.debug.WeatherHistory
import actron.example.weather.app.debug.WeatherSettings
import actron.example.weather.app.debug.restorableWeatherRoot
import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.feature.cities.presentation.CitySearchComponent
import actron.example.weather.feature.cities.presentation.CitySearchIntent
import actron.example.weather.feature.cities.presentation.toSearchSelection
import actron.example.weather.feature.weather.presentation.CityWeatherComponent
import actron.example.weather.feature.weather.presentation.CityWeatherIntent
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WeatherContinuationTest {
    @Test
    fun continuesSearchFromHistoryAfterOpeningWeatherAndCancelsTheOldRequests() =
        runTest {
            val lifecycle = LifecycleRegistry().apply { resume() }
            val history = WeatherHistory()
            var cancelled = 0
            val root =
                restorableWeatherRoot(
                    DefaultComponentContext(lifecycle),
                    weather = {
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled++
                        }
                    },
                    cities = { emptyList() },
                    hourly = {
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled++
                        }
                    },
                    history = history,
                    settings = WeatherSettings(),
                    uiContext = coroutineContext,
                )
            try {
                testScheduler.runCurrent()
                val search =
                    assertIs<CitySearchComponent>(
                        root.component.value
                            .getChildStack()
                            .value.active.instance,
                    )
                search.onUiEvent(CitySearchIntent.QueryChanged("Rome"))
                testScheduler.runCurrent()
                val point =
                    history.timeline.entries.value.last { entry ->
                        val display = entry.snapshot.screen
                        display is WeatherDisplay.Search && display.state.query == "Rome" && entry.snapshot.continuation is SessionContinuation.Ready
                    }
                search.onUiEvent(demoCities.first().toSearchSelection())
                testScheduler.runCurrent()
                assertIs<CityWeatherComponent>(
                    root.component.value
                        .getChildStack()
                        .value.active.instance,
                )
                history.continueFrom(point.snapshot)
                testScheduler.runCurrent()
                val restored =
                    assertIs<CitySearchComponent>(
                        root.component.value
                            .getChildStack()
                            .value.active.instance,
                    )
                assertEquals("Rome", restored.state.value.query)
                assertTrue(cancelled >= 2)
                restored.onUiEvent(CitySearchIntent.QueryChanged("Paris"))
                testScheduler.runCurrent()
                assertEquals("Paris", restored.state.value.query)
            } finally {
                root.close()
                lifecycle.destroy()
            }
        }

    @Test
    fun restoresAClosedWeatherScreenAndCanPersistTheSelectedPoint() =
        runTest {
            val lifecycle = LifecycleRegistry().apply { resume() }
            val history = WeatherHistory()
            val root =
                restorableWeatherRoot(
                    DefaultComponentContext(lifecycle),
                    weather = { awaitCancellation() },
                    cities = { emptyList() },
                    hourly = { awaitCancellation() },
                    history = history,
                    settings = WeatherSettings(),
                    uiContext = coroutineContext,
                )
            try {
                testScheduler.runCurrent()
                assertIs<CitySearchComponent>(
                    root.component.value
                        .getChildStack()
                        .value.active.instance,
                ).onUiEvent(demoCities.first().toSearchSelection())
                testScheduler.runCurrent()
                val point =
                    history.timeline.entries.value
                        .last { it.snapshot.screen is WeatherDisplay.Weather && it.snapshot.continuation is SessionContinuation.Ready }
                val checkpoint = assertIs<SessionContinuation.Ready>(point.snapshot.continuation).checkpoint
                val saved = root.encode(checkpoint)
                assertIs<CityWeatherComponent>(
                    root.component.value
                        .getChildStack()
                        .value.active.instance,
                ).onUiEvent(CityWeatherIntent.Back)
                testScheduler.runCurrent()
                assertIs<CitySearchComponent>(
                    root.component.value
                        .getChildStack()
                        .value.active.instance,
                )
                root.continueFrom(root.decode(saved))
                testScheduler.runCurrent()
                assertEquals(
                    demoCities.first().name,
                    assertIs<CityWeatherComponent>(
                        root.component.value
                            .getChildStack()
                            .value.active.instance,
                    ).state.value.city,
                )
            } finally {
                root.close()
                lifecycle.destroy()
            }
        }
}
