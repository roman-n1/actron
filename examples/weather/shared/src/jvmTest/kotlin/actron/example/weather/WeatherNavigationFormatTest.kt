package actron.example.weather

import actron.example.weather.app.assembly.DefaultCitySearchComponentFactory
import actron.example.weather.app.assembly.DefaultCityWeatherComponentFactory
import actron.example.weather.app.root.WeatherRootComponent
import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.feature.cities.data.LocalCitySearchRepository
import actron.example.weather.feature.cities.presentation.CitySearchComponent
import actron.example.weather.feature.cities.presentation.CitySearchIntent
import actron.example.weather.feature.cities.presentation.toSearchSelection
import actron.example.weather.feature.weather.data.SampleHourlyRepository
import actron.example.weather.feature.weather.data.SampleWeatherRepository
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WeatherNavigationFormatTest {
    @Test
    fun routeNamesRemainCompatibleWithSavedNavigation() =
        runTest {
            val lifecycle = LifecycleRegistry()
            val context = DefaultComponentContext(lifecycle)
            val root =
                WeatherRootComponent(
                    componentContext = context,
                    uiContext = coroutineContext,
                    searchFactory =
                        DefaultCitySearchComponentFactory(
                            repository = LocalCitySearchRepository,
                            uiContext = coroutineContext,
                            machineContext = coroutineContext,
                        ),
                    weatherFactory =
                        DefaultCityWeatherComponentFactory(
                            repository = SampleWeatherRepository,
                            hourlyRepository = SampleHourlyRepository,
                            uiContext = coroutineContext,
                            machineContext = coroutineContext,
                        ),
                )
            lifecycle.resume()
            try {
                testScheduler.runCurrent()
                val search = root.getChildStack().value.active
                assertEquals(
                    "actron.example.weather.app.DefaultWeatherRootComponent.ChildConfiguration.Search",
                    serializer(search.configuration.javaClass).descriptor.serialName,
                )
                assertIs<CitySearchComponent>(search.instance).onUiEvent(demoCities.first().toSearchSelection())
                testScheduler.runCurrent()
                val weather = root.getChildStack().value.active
                assertEquals(
                    "actron.example.weather.app.DefaultWeatherRootComponent.ChildConfiguration.Weather",
                    serializer(weather.configuration.javaClass).descriptor.serialName,
                )
            } finally {
                lifecycle.destroy()
            }
        }
}
