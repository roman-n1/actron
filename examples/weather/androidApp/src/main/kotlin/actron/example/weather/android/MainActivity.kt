package actron.example.weather.android

import actron.example.weather.app.DefaultWeatherRootComponent
import actron.example.weather.app.WeatherTheme
import actron.example.weather.core.network.weatherHttpClient
import actron.example.weather.feature.cities.data.OpenMeteoCitySearchRepository
import actron.example.weather.feature.weather.data.OpenMeteoHourlyRepository
import actron.example.weather.feature.weather.data.OpenMeteoWeatherRepository
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.arkivanov.decompose.defaultComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate

class MainActivity : ComponentActivity() {
    init {
        // ON_CREATE runs after SavedStateRegistry restoration, with the Activity attached.
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onCreate(owner: LifecycleOwner) {
                    showWeather()
                }
            },
        )
    }

    private fun showWeather() {
        val context = defaultComponentContext()
        val network = context.instanceKeeper.getOrCreate { NetworkDependencies() }
        val root = createWeatherRoot(context, network.weather, network.cities, network.hourly)
        setContent {
            WeatherTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) { root.Render() }
            }
        }
    }
}

private class NetworkDependencies : InstanceKeeper.Instance {
    private val client = weatherHttpClient()
    val weather = OpenMeteoWeatherRepository(client)
    val cities = OpenMeteoCitySearchRepository(client)
    val hourly = OpenMeteoHourlyRepository(client)

    override fun onDestroy() {
        client.close()
    }
}
