package actron.example.weather.app.debug

import actron.core.Action
import actron.core.Event
import actron.decompose.MachineDiagnostics
import actron.decompose.MachineRecording
import actron.example.weather.app.session.WeatherSession
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.feature.cities.presentation.CitySearchAction
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.example.weather.feature.cities.presentation.SearchCitiesCommand
import actron.example.weather.feature.weather.presentation.WeatherAction
import actron.example.weather.feature.weather.presentation.WeatherCommand
import actron.example.weather.feature.weather.presentation.WeatherContext
import actron.statechart.machine.MachineProfiler
import actron.statechart.machine.profileWith
import actron.timetravel.historyFields
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

internal fun <C : Any, A : Action, CMD : Any, E : Event> WeatherHistory.weatherRecording(): MachineRecording<C, A, CMD, E> =
    MachineRecording { machine, checkpoint, handler ->
        val profiler = MachineProfiler(machine).apply { beginAt(checkpoint.snapshot) }
        val observer = observer(machine, checkpoint, { describeWeatherContext(it) }, profiler)
        MachineDiagnostics(profiler.commands(handler), listOf(profiler, observer)) { profileWith(profiler) }
    }

private fun describeWeatherContext(value: Any) =
    historyFields(
        when (value) {
            is CitySearchState -> Json.encodeToJsonElement(CitySearchState.serializer(), value)
            is WeatherContext -> Json.encodeToJsonElement(WeatherContext.serializer(), value)
            else -> error("Unknown Weather context")
        },
    )
