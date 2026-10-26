package actron.example.weather.app.session

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
data class WeatherSavedState(
    val format: Int = 3,
    val checkpoints: String,
    val branch: Long,
)

object WeatherSavedStateCodec {
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

    fun encode(history: WeatherSession): WeatherSavedState = WeatherSavedState(checkpoints = group.save(history.persistenceFrame()), branch = history.branch)

    fun decodeSession(
        saved: WeatherSavedState,
        clockFactory: () -> MachineClock = { MachineClock.monotonic() },
    ): WeatherSession {
        require(saved.format == 3 && saved.branch >= 0)
        return WeatherSession(initialFrame = group.restore(saved.checkpoints), initialBranch = saved.branch, clockFactory = clockFactory)
    }
}

fun retainedWeatherSession(context: ComponentContext): WeatherSession =
    context.instanceKeeper.getOrCreate(key = "weather-session") {
        try {
            context.stateKeeper.consume("weather-checkpoint", WeatherSavedState.serializer())?.let { saved ->
                return@getOrCreate WeatherSavedStateCodec.decodeSession(saved)
            }
        } catch (_: IllegalArgumentException) {
            return@getOrCreate WeatherSession()
        }
        WeatherSession()
    }
