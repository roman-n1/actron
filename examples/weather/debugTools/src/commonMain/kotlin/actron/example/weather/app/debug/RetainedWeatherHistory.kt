package actron.example.weather.app.debug

import actron.timetravel.HistoryConfiguration
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate

/** Keeps optional recording across host recreation. */
fun retainedWeatherHistory(
    context: ComponentContext,
    configuration: HistoryConfiguration = HistoryConfiguration(),
): WeatherHistory =
    context.instanceKeeper.getOrCreate(key = "weather-debug-history") {
        WeatherHistory(capacity = configuration.capacity)
    }
