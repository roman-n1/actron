package actron.example.weather

import actron.example.weather.app.WeatherTheme
import actron.example.weather.app.debug.DebugWeatherRootComponent
import actron.example.weather.app.debug.IosRecordingFiles
import actron.example.weather.app.debug.LocalIosRecordingFiles
import actron.example.weather.core.network.weatherHttpClient
import actron.example.weather.feature.cities.data.OpenMeteoCitySearchRepository
import actron.example.weather.feature.weather.data.OpenMeteoHourlyRepository
import actron.example.weather.feature.weather.data.OpenMeteoWeatherRepository
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.ApplicationLifecycle
import platform.UIKit.UIViewController

fun MainViewController(files: IosRecordingFiles): UIViewController =
    ComposeUIViewController {
        val client = remember { weatherHttpClient() }
        val lifecycle = remember { ApplicationLifecycle() }
        val root =
            remember {
                DebugWeatherRootComponent(
                    DefaultComponentContext(lifecycle),
                    OpenMeteoWeatherRepository(client),
                    OpenMeteoCitySearchRepository(client),
                    OpenMeteoHourlyRepository(client),
                )
            }
        DisposableEffect(root) {
            onDispose {
                lifecycle.destroy()
                client.close()
            }
        }
        CompositionLocalProvider(LocalIosRecordingFiles provides files) {
            WeatherTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) { root.Render() }
            }
        }
    }
