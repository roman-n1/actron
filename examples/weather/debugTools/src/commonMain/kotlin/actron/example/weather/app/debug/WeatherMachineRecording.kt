package actron.example.weather.app.debug

import actron.core.Action
import actron.core.Event
import actron.decompose.MachineDiagnostics
import actron.decompose.MachineRecording
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.example.weather.feature.weather.presentation.WeatherContext
import actron.timetravel.historyFields
import kotlinx.serialization.json.Json

internal fun <C : Any, A : Action, CMD : Any, E : Event> WeatherHistory.recording(): MachineRecording<C, A, CMD, E> =
    MachineRecording { machine, checkpoint, handler ->
        val observer = observer(machine, checkpoint, { describeWeatherContext(it) })
        MachineDiagnostics(handler, listOf(observer))
    }

private fun describeWeatherContext(value: Any) =
    historyFields(
        when (value) {
            is CitySearchState -> Json.encodeToJsonElement(CitySearchState.serializer(), value)
            is WeatherContext -> Json.encodeToJsonElement(WeatherContext.serializer(), value)
            else -> error("Unknown Weather context")
        },
    )
