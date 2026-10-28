package actron.example.weather.app.debug

import actron.core.Action
import actron.core.Event
import actron.decompose.MachineDiagnostics
import actron.decompose.MachineRecording
import actron.example.weather.app.WeatherMachineRecordings
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherContext
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastContext
import actron.example.weather.feature.weather.presentation.WeatherContext
import actron.example.weather.feature.weather.presentation.WeatherScreenContext
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceContext
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
            is WeatherScreenContext -> Json.encodeToJsonElement(WeatherScreenContext.serializer(), value)
            is CurrentWeatherContext -> Json.encodeToJsonElement(CurrentWeatherContext.serializer(), value)
            is HourlyForecastContext -> Json.encodeToJsonElement(HourlyForecastContext.serializer(), value)
            is WeatherAdviceContext -> Json.encodeToJsonElement(WeatherAdviceContext.serializer(), value)
            is WeatherContext -> Json.encodeToJsonElement(WeatherContext.serializer(), value)
            else -> error("Unknown Weather context")
        },
    )

internal fun WeatherHistory.weatherRecordings(): WeatherMachineRecordings = WeatherMachineRecordings(screen = recording(), current = recording(), hourly = recording(), advice = recording())
