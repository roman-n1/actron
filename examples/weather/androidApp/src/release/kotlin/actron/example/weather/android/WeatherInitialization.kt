package actron.example.weather.android

import actron.example.weather.app.assembly.DefaultCitySearchComponentFactory
import actron.example.weather.app.assembly.DefaultCityWeatherComponentFactory
import actron.example.weather.app.root.WeatherRootComponent
import actron.example.weather.feature.cities.domain.CitySearchRepository
import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.example.weather.feature.weather.domain.WeatherRepository
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.Dispatchers

@Composable
internal fun WeatherContent(
    context: ComponentContext,
    weather: WeatherRepository,
    cities: CitySearchRepository,
    hourly: HourlyForecastRepository,
) {
    val root =
        remember(context, weather, cities, hourly) {
            val uiContext = Dispatchers.Main
            val machineContext = Dispatchers.Default.limitedParallelism(1)
            WeatherRootComponent(
                componentContext = context,
                uiContext = uiContext,
                searchFactory =
                    DefaultCitySearchComponentFactory(
                        repository = cities,
                        uiContext = uiContext,
                        machineContext = machineContext,
                    ),
                weatherFactory =
                    DefaultCityWeatherComponentFactory(
                        repository = weather,
                        hourlyRepository = hourly,
                        uiContext = uiContext,
                        machineContext = machineContext,
                    ),
            )
        }
    root.Render()
}
