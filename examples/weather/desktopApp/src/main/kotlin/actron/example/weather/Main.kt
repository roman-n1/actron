package actron.example.weather

import actron.example.weather.app.DefaultWeatherRootComponent
import actron.example.weather.app.WeatherTheme
import actron.example.weather.core.network.weatherHttpClient
import actron.example.weather.feature.cities.data.LocalCitySearchRepository
import actron.example.weather.feature.cities.data.OpenMeteoCitySearchRepository
import actron.example.weather.feature.weather.data.OpenMeteoHourlyRepository
import actron.example.weather.feature.weather.data.OpenMeteoWeatherRepository
import actron.example.weather.feature.weather.data.SampleHourlyRepository
import actron.example.weather.feature.weather.data.SampleWeatherRepository
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing

/** `--offline` selects sample data; live APIs are the default. */
fun main(args: Array<String>) {
    val client = weatherHttpClient()
    val offline = "--offline" in args
    val hourlyApi = if (offline) SampleHourlyRepository else OpenMeteoHourlyRepository(client)
    val cities = if (offline) LocalCitySearchRepository else OpenMeteoCitySearchRepository(client)
    val api = if (offline) SampleWeatherRepository else OpenMeteoWeatherRepository(client)
    try {
        val lifecycle = LifecycleRegistry()
        try {
            val root =
                runBlocking(Dispatchers.Swing) {
                    DefaultWeatherRootComponent(DefaultComponentContext(lifecycle), api, cities, hourlyApi)
                        .also { lifecycle.resume() }
                }
            application(exitProcessOnExit = false) {
                Window(onCloseRequest = ::exitApplication, title = "Weather", state = rememberWindowState(size = DpSize(1180.dp, 900.dp))) {
                    WeatherTheme { root.Render() }
                }
            }
        } finally {
            runBlocking(Dispatchers.Swing) { lifecycle.destroy() }
        }
    } finally {
        client.close()
    }
}
