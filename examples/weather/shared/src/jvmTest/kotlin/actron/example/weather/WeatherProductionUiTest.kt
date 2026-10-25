package actron.example.weather

import actron.example.weather.app.DefaultWeatherRootComponent
import actron.example.weather.app.WeatherTheme
import actron.example.weather.feature.cities.data.LocalCitySearchRepository
import actron.example.weather.feature.weather.data.SampleHourlyRepository
import actron.example.weather.feature.weather.data.SampleWeatherRepository
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import kotlin.test.Test
import kotlin.test.assertFailsWith

@OptIn(ExperimentalTestApi::class)
class WeatherProductionUiTest {
    @Test
    fun productionRuntimeDoesNotContainDebuggerClasses() {
        listOf("actron.timetravel.BoundedMachineRecorder", "actron.timetravel.compose.HistoryPanelKt", "actron.example.weather.app.debug.WeatherHistory").forEach { name ->
            assertFailsWith<ClassNotFoundException> {
                Class.forName(name)
                Unit
            }
        }
    }

    @Test
    fun normalScreenHasNoDebugEntryPoint() =
        runComposeUiTest {
            val lifecycle = LifecycleRegistry()
            val root =
                runOnUiThread {
                    DefaultWeatherRootComponent(DefaultComponentContext(lifecycle), SampleWeatherRepository, LocalCitySearchRepository, SampleHourlyRepository)
                }
            setContent {
                DisposableEffect(root) {
                    lifecycle.resume()
                    onDispose { lifecycle.destroy() }
                }
                WeatherTheme { root.Render() }
            }
            onNodeWithTag("cities-screen").assertExists()
            onNodeWithTag("city-search").assertExists()
            onNodeWithTag("debug-settings").assertDoesNotExist()
            onNodeWithTag("weather-history-panel").assertDoesNotExist()
        }
}
