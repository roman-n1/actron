package actron.example.weather

import actron.example.weather.app.debug.WeatherHistory
import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.feature.cities.data.LocalCitySearchRepository
import actron.example.weather.feature.cities.presentation.CitySearchComponent
import actron.example.weather.feature.cities.presentation.CitySearchIntent
import actron.example.weather.feature.cities.presentation.toSearchSelection
import actron.example.weather.feature.weather.data.SampleHourlyRepository
import actron.example.weather.feature.weather.data.SampleWeatherRepository
import actron.example.weather.feature.weather.domain.WeatherRepository
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.backhandler.BackDispatcher
import com.arkivanov.essenty.instancekeeper.InstanceKeeperDispatcher
import com.arkivanov.essenty.instancekeeper.getOrCreate
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WeatherRootComponentTest {
    @Test
    fun searchEntryUsesMachineContext() =
        runTest {
            val keeper = InstanceKeeperDispatcher()
            val lifecycle = LifecycleRegistry()
            val entryContext = kotlinx.coroutines.CompletableDeferred<String>()
            val root =
                weatherRootFixture(
                    DefaultComponentContext(lifecycle, instanceKeeper = keeper),
                    SampleWeatherRepository,
                    {
                        entryContext.complete(
                            kotlinx.coroutines
                                .currentCoroutineContext()[kotlinx.coroutines.CoroutineName]
                                ?.name
                                .orEmpty(),
                        )
                        emptyList()
                    },
                    SampleHourlyRepository,
                    uiContext = coroutineContext + kotlinx.coroutines.CoroutineName("ui"),
                    machineContext = coroutineContext + kotlinx.coroutines.CoroutineName("machine"),
                )
            lifecycle.resume()
            try {
                testScheduler.runCurrent()
                val search =
                    root
                        .getChildStack()
                        .value.active.instance as CitySearchComponent
                search.onUiEvent(CitySearchIntent.QueryChanged("Paris"))
                assertEquals("machine", entryContext.await())
            } finally {
                lifecycle.destroy()
                keeper.destroy()
            }
        }

    @Test
    fun backReturnsToSameSearch_andRecreationRetainsQuery() =
        runTest {
            val keeper = InstanceKeeperDispatcher()
            val lifecycle = LifecycleRegistry()
            val back = BackDispatcher()
            val stateKeeper =
                com.arkivanov.essenty.statekeeper
                    .StateKeeperDispatcher()
            val root =
                weatherRootFixture(
                    DefaultComponentContext(lifecycle, instanceKeeper = keeper, backHandler = back, stateKeeper = stateKeeper),
                    SampleWeatherRepository,
                    LocalCitySearchRepository,
                    SampleHourlyRepository,
                    uiContext = coroutineContext,
                    machineContext = coroutineContext,
                )
            lifecycle.resume()
            try {
                testScheduler.runCurrent()
                val search =
                    root
                        .getChildStack()
                        .value.active.instance as CitySearchComponent
                search.onUiEvent(CitySearchIntent.QueryChanged("Lis"))
                testScheduler.runCurrent()
                assertEquals("Lis", search.state.value.query)
                search.onUiEvent(demoCities[1].toSearchSelection())
                testScheduler.runCurrent()
                kotlin.test.assertIs<actron.example.weather.feature.weather.presentation.CityWeatherComponent>(
                    root
                        .getChildStack()
                        .value.active.instance,
                )
                back.back()
                testScheduler.runCurrent()
                kotlin.test.assertIs<CitySearchComponent>(
                    root
                        .getChildStack()
                        .value.active.instance,
                )
                assertSame(
                    search,
                    root
                        .getChildStack()
                        .value.active.instance,
                )
                assertEquals("Lis", search.state.value.query)
                val saved = stateKeeper.save()
                lifecycle.destroy()

                val recreatedLifecycle = LifecycleRegistry()
                val recreated =
                    weatherRootFixture(
                        DefaultComponentContext(
                            recreatedLifecycle,
                            instanceKeeper = keeper,
                            stateKeeper =
                                com.arkivanov.essenty.statekeeper
                                    .StateKeeperDispatcher(saved),
                        ),
                        SampleWeatherRepository,
                        LocalCitySearchRepository,
                        SampleHourlyRepository,
                        uiContext = coroutineContext,
                        machineContext = coroutineContext,
                    )
                recreatedLifecycle.resume()
                testScheduler.runCurrent()
                assertEquals(
                    "Lis",
                    (
                        recreated
                            .getChildStack()
                            .value.active.instance as CitySearchComponent
                    ).state.value.query,
                )
                recreatedLifecycle.destroy()
            } finally {
                lifecycle.destroy()
                keeper.destroy()
            }
        }

    @Test
    fun destroyingHostCancelsInFlightWeather() =
        runTest {
            var started = false
            var cancelled = false
            val api =
                WeatherRepository {
                    started = true
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            val lifecycle = LifecycleRegistry()
            val root =
                weatherRootFixture(
                    DefaultComponentContext(lifecycle),
                    api,
                    LocalCitySearchRepository,
                    SampleHourlyRepository,
                    uiContext = coroutineContext,
                    machineContext = coroutineContext,
                )
            lifecycle.resume()
            try {
                testScheduler.runCurrent()
                (
                    root
                        .getChildStack()
                        .value.active.instance as CitySearchComponent
                ).onUiEvent(demoCities[1].toSearchSelection())
                testScheduler.runCurrent()
                assertTrue(started)
            } finally {
                lifecycle.destroy()
            }
            testScheduler.runCurrent()
            assertTrue(cancelled)
        }

    @Test
    fun poppingWeatherCancelsBothRequestsAndNextCityStartsFresh() =
        runTest {
            val current = mutableListOf<actron.example.weather.core.domain.City>()
            val hourly = mutableListOf<actron.example.weather.core.domain.City>()
            var cancelled = 0
            val lifecycle = LifecycleRegistry()
            val root =
                weatherRootFixture(
                    DefaultComponentContext(lifecycle),
                    WeatherRepository { city ->
                        current += city
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled++
                        }
                    },
                    LocalCitySearchRepository,
                    actron.example.weather.feature.weather.domain.HourlyForecastRepository { city ->
                        hourly += city
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled++
                        }
                    },
                    uiContext = coroutineContext,
                    machineContext = coroutineContext,
                )
            lifecycle.resume()
            try {
                testScheduler.runCurrent()
                val search =
                    root
                        .getChildStack()
                        .value.active.instance as CitySearchComponent
                search.onUiEvent(demoCities[1].toSearchSelection())
                testScheduler.runCurrent()
                val weather =
                    root
                        .getChildStack()
                        .value.active.instance as actron.example.weather.feature.weather.presentation.CityWeatherComponent
                weather.onUiEvent(actron.example.weather.feature.weather.presentation.CityWeatherIntent.Back)
                testScheduler.runCurrent()
                assertEquals(3, cancelled)
                assertSame(
                    search,
                    root
                        .getChildStack()
                        .value.active.instance,
                )
                search.onUiEvent(demoCities[2].toSearchSelection())
                testScheduler.runCurrent()
                assertEquals(listOf(demoCities[1], demoCities[1], demoCities[2], demoCities[2]), current)
                assertEquals(listOf(demoCities[1], demoCities[2]), hourly)
            } finally {
                lifecycle.destroy()
            }
            testScheduler.runCurrent()
            assertEquals(6, cancelled)
        }
}
