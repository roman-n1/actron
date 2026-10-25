@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.example.weather.app.debug

import actron.example.weather.feature.cities.presentation.*
import actron.example.weather.feature.weather.presentation.*
import actron.statechart.machine.MachineClock
import actron.timetravel.checkpointGroup
import actron.timetravel.machinePersistence
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import kotlinx.serialization.Serializable

/** Only the current application checkpoint is saved; the debug timeline is session-local. */
@Serializable
internal data class WeatherSavedState(
    val format: Int = 3,
    val checkpoints: String,
    val branch: Long,
)

internal object WeatherSavedStateCodec {
    private val group =
        // Generic codecs validate all machines; these read/write adapters alone know Weather's frame.
        checkpointGroup(::WeatherFrame) {
            machine(machinePersistence(CitySearchMachine.create()), read = { it.search }, write = { frame, search -> frame.copy(search = search) })
            // A collection models an optional weather child without introducing a nullable checkpoint.
            machines(machinePersistence(CityWeatherMachine.create()), read = { frame ->
                when (val route = frame.route) {
                    WeatherRoute.Search -> emptyList()
                    is WeatherRoute.Weather -> listOf(route.checkpoint)
                }
            }, write = { frame, weather ->
                frame.copy(route = if (weather.isEmpty()) WeatherRoute.Search else WeatherRoute.Weather(weather.single()))
            })
        }

    fun encode(history: WeatherHistory): WeatherSavedState = WeatherSavedState(checkpoints = group.save(history.persistenceFrame()), branch = history.branch)

    fun decode(
        saved: WeatherSavedState,
        clockFactory: () -> MachineClock = { MachineClock.monotonic() },
    ): WeatherHistory {
        require(saved.format == 3 && saved.branch >= 0)
        val frame = group.restore(saved.checkpoints)
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
