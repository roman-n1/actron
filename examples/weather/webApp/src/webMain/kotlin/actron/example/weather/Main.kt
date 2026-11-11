@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package actron.example.weather

import actron.example.weather.app.debug.Render
import actron.example.weather.app.debug.WeatherDebugLayout
import actron.example.weather.app.debug.WeatherSettings
import actron.example.weather.app.debug.restorableWeatherRoot
import actron.example.weather.app.debug.retainedWeatherHistory
import actron.example.weather.app.theme.WeatherTheme
import actron.example.weather.core.network.weatherHttpClient
import actron.example.weather.feature.cities.data.OpenMeteoCitySearchRepository
import actron.example.weather.feature.weather.data.OpenMeteoHourlyRepository
import actron.example.weather.feature.weather.data.OpenMeteoWeatherRepository
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.ComposeViewport
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.Dispatchers

fun main() {
    val client = weatherHttpClient()
    val lifecycle = LifecycleRegistry()
    val componentContext = DefaultComponentContext(lifecycle)
    val history = retainedWeatherHistory(componentContext)
    val settings = WeatherSettings()
    val uiContext = Dispatchers.Main
    val root =
        restorableWeatherRoot(context = componentContext, weather = OpenMeteoWeatherRepository(client), cities = OpenMeteoCitySearchRepository(client), hourly = OpenMeteoHourlyRepository(client), history = history, settings = settings, uiContext = uiContext)
    ComposeViewport {
        DisposableEffect(root) {
            lifecycle.resume()
            onDispose {
                lifecycle.destroy()
                client.close()
            }
        }
        WeatherTheme {
            WeatherDebugLayout(
                history = history,
                settings = settings,
            ) { root.Render() }
        }
    }
}
