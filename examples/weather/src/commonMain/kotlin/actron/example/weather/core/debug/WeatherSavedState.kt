@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.example.weather.core.debug

import actron.example.weather.feature.cities.presentation.*
import actron.example.weather.feature.weather.presentation.*
import actron.statechart.machine.MachineClock
import actron.timetravel.machinePersistence
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import kotlinx.serialization.Serializable

/** Only the current application checkpoint is saved; the debug timeline is session-local. */
@Serializable
internal data class WeatherSavedState(
    val format: Int = 1,
    val search: String,
    val weather: List<String>,
    val branch: Long,
)

internal object WeatherSavedStateCodec {
    private val searchMachine = CitySearchMachine.create()
    private val weatherMachine = CityWeatherMachine.create()
    private val searchPersistence = machinePersistence(searchMachine)
    private val weatherPersistence = machinePersistence(weatherMachine)

    fun encode(history: WeatherHistory): WeatherSavedState {
        val frame = history.persistenceFrame()
        return WeatherSavedState(
            search = searchPersistence.save(frame.search),
            weather =
                when (val route = frame.route) {
                    WeatherRoute.Search -> emptyList()
                    is WeatherRoute.Weather -> listOf(weatherPersistence.save(route.checkpoint))
                },
            branch = history.branch,
        )
    }

    fun decode(
        saved: WeatherSavedState,
        clockFactory: () -> MachineClock = { MachineClock.monotonic() },
    ): WeatherHistory {
        require(saved.format == 1)
        require(saved.weather.size <= 1 && saved.branch >= 0)
        val frame =
            WeatherFrame(
                search = searchPersistence.restore(saved.search),
                route =
                    saved.weather.fold<String, WeatherRoute>(WeatherRoute.Search) { _, text ->
                        WeatherRoute.Weather(weatherPersistence.restore(text))
                    },
            )
        require(frame.canResume())
        return WeatherHistory(initialFrame = frame, initialBranch = saved.branch, clockFactory = clockFactory)
    }
}

internal fun retainedWeatherHistory(context: ComponentContext): WeatherHistory {
    val saved =
        try {
            context.stateKeeper.consume("weather-checkpoint", WeatherSavedState.serializer())?.let { listOf(it) } ?: emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
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
