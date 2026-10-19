package actron.example.weather.core.debug

import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.feature.cities.presentation.*
import actron.example.weather.feature.weather.presentation.*
import actron.statechart.machine.MachineClock
import actron.timetravel.RecordingCodec
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import kotlinx.serialization.Serializable

/** Only the current application checkpoint is saved; the debug timeline is session-local. */
@Serializable
internal data class WeatherSavedState(
    val format: Int = 1,
    val searchVersion: String,
    val weatherVersion: String,
    val search: String,
    val weather: List<String>,
    val branch: Long,
)

internal object WeatherSavedStateCodec {
    private val searchMachine = CitySearchMachine.create()
    private val weatherMachine = CityWeatherMachine.create()
    private val searchCodec = RecordingCodec(CitySearchState.serializer(), CitySearchAction.serializer(), SearchCitiesCommand.serializer(), NoEvent.serializer())
    private val weatherCodec = RecordingCodec(WeatherContext.serializer(), WeatherAction.serializer(), WeatherCommand.serializer(), NoEvent.serializer())

    fun encode(history: WeatherHistory): WeatherSavedState {
        val frame = history.persistenceFrame()
        return WeatherSavedState(
            searchVersion = searchMachine.version.value,
            weatherVersion = weatherMachine.version.value,
            search = searchCodec.encodeCheckpoint(frame.search),
            weather =
                when (val route = frame.route) {
                    WeatherRoute.Search -> emptyList()
                    is WeatherRoute.Weather -> listOf(weatherCodec.encodeCheckpoint(route.checkpoint))
                },
            branch = history.branch,
        )
    }

    fun decode(
        saved: WeatherSavedState,
        clockFactory: () -> MachineClock = { MachineClock.monotonic() },
    ): WeatherHistory {
        require(saved.format == 1 && saved.searchVersion == searchMachine.version.value && saved.weatherVersion == weatherMachine.version.value)
        require(saved.weather.size <= 1 && saved.branch >= 0)
        val frame =
            WeatherFrame(
                search = searchCodec.decodeCheckpoint(saved.search, searchMachine.id, searchMachine.version),
                route =
                    saved.weather.fold<String, WeatherRoute>(WeatherRoute.Search) { _, text ->
                        WeatherRoute.Weather(weatherCodec.decodeCheckpoint(text, weatherMachine.id, weatherMachine.version))
                    },
            )
        require(frame.canResume())
        return WeatherHistory(initialFrame = frame, initialBranch = saved.branch, clockFactory = clockFactory)
    }
}

internal fun retainedWeatherHistory(context: ComponentContext): WeatherHistory {
    val saved = context.stateKeeper.consume("weather-checkpoint", WeatherSavedState.serializer())?.let { listOf(it) } ?: emptyList()
    return context.instanceKeeper.getOrCreate(key = "weather-history") {
        saved.fold(WeatherHistory()) { _, value ->
            try {
                WeatherSavedStateCodec.decode(value)
            } catch (_: IllegalArgumentException) {
                // A changed machine definition or invalid checkpoint starts a fresh session.
                WeatherHistory()
            }
        }
    }
}
