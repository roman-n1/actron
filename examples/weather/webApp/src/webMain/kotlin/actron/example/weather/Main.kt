@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package actron.example.weather

import actron.example.weather.app.WeatherTheme
import actron.example.weather.app.debug.DebugWeatherRootComponent
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

fun main() {
    val client = weatherHttpClient()
    val api = OpenMeteoWeatherRepository(client)
    val hourlyApi = OpenMeteoHourlyRepository(client)
    val cities = OpenMeteoCitySearchRepository(client)
    val lifecycle = LifecycleRegistry()
    val root = DebugWeatherRootComponent(DefaultComponentContext(lifecycle), api, cities, hourlyApi)
    ComposeViewport {
        DisposableEffect(root) {
            lifecycle.resume()
            onDispose {
                lifecycle.destroy()
                client.close()
            }
        }
        WeatherTheme { root.Render() }
    }
}
