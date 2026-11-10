package actron.example.weather.app.debug

import actron.core.Action
import actron.core.Event
import actron.decompose.MachineDiagnostics
import actron.decompose.MachineRecording
import actron.example.weather.app.assembly.WeatherMachineRecordings
import actron.example.weather.core.domain.City
import actron.example.weather.feature.cities.presentation.CitySearchAction
import actron.example.weather.feature.cities.presentation.CitySearchEvent
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherState
import actron.example.weather.feature.currentweather.presentation.toRequestState
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastState
import actron.example.weather.feature.hourlyforecast.presentation.toRequestState
import actron.example.weather.feature.weather.presentation.WeatherContext
import actron.example.weather.feature.weather.presentation.WeatherScreenState
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceState
import actron.example.weather.feature.weatheradvice.presentation.toAdvicePhase
import actron.timetravel.historyFields
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.Json

fun WeatherHistory.recording(): MachineRecording<CitySearchState, CitySearchAction, Unit, CitySearchEvent> = recording { WeatherDisplay.Search(it) }

private fun <C : Any, A : Action, CMD : Any, E : Event> WeatherHistory.recording(
    project: (C) -> WeatherDisplay,
): MachineRecording<C, A, CMD, E> =
    MachineRecording { machine, checkpoint, handler ->
        val observer = observer(machine, checkpoint, { describeWeatherContext(it) }, project)
        MachineDiagnostics(handler, listOf(observer))
    }

/** Each weather component gets its own diagnostic projection, never used by live machines. */
fun WeatherHistory.weatherRecordings(city: City): WeatherMachineRecordings {
    val lock = SynchronizedObject()
    var context = WeatherContext(city)

    fun snapshot(update: (WeatherContext) -> WeatherContext): WeatherDisplay =
        synchronized(lock) {
            context = update(context)
            WeatherDisplay.Weather(context)
        }

    return WeatherMachineRecordings(
        screen = recording { parent -> snapshot { it.copy(city = parent.city) } },
        current = recording { child -> snapshot { it.copy(current = child.toRequestState()) } },
        hourly = recording { child -> snapshot { it.copy(hourly = child.toRequestState()) } },
        advice = recording { child -> snapshot { it.copy(advice = child.toAdvicePhase()) } },
    )
}

private fun describeWeatherContext(value: Any) =
    historyFields(
        when (value) {
            is CitySearchState -> Json.encodeToJsonElement(CitySearchState.serializer(), value)
            is WeatherScreenState -> Json.encodeToJsonElement(WeatherScreenState.serializer(), value)
            is CurrentWeatherState -> Json.encodeToJsonElement(CurrentWeatherState.serializer(), value)
            is HourlyForecastState -> Json.encodeToJsonElement(HourlyForecastState.serializer(), value)
            is WeatherAdviceState -> Json.encodeToJsonElement(WeatherAdviceState.serializer(), value)
            is WeatherContext -> Json.encodeToJsonElement(WeatherContext.serializer(), value)
            else -> error("Unknown Weather context")
        },
    )
