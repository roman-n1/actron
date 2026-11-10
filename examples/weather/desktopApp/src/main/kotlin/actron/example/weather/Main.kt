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
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.Dispatchers

fun main() =
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Weather",
            state = rememberWindowState(size = DpSize(1180.dp, 900.dp)),
        ) {
            val lifecycle = remember { LifecycleRegistry() }
            val client = remember { weatherHttpClient() }
            val componentContext = remember { DefaultComponentContext(lifecycle) }
            val history = remember { retainedWeatherHistory(componentContext) }
            val settings = remember { WeatherSettings() }
            val root =
                remember {
                    val uiContext = Dispatchers.Main
                    restorableWeatherRoot(context = componentContext, weather = OpenMeteoWeatherRepository(client), cities = OpenMeteoCitySearchRepository(client), hourly = OpenMeteoHourlyRepository(client), history = history, settings = settings, uiContext = uiContext)
                }
            DisposableEffect(root) {
                lifecycle.resume()
                onDispose {
                    try {
                        lifecycle.destroy()
                    } finally {
                        client.close()
                    }
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
