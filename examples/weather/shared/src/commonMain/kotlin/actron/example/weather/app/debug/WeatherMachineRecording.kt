package actron.example.weather.app.debug

import actron.core.Action
import actron.core.Event
import actron.example.weather.core.component.MachineRecording
import actron.example.weather.core.component.MachineSetup
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.feature.cities.presentation.CitySearchAction
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.example.weather.feature.cities.presentation.SearchCitiesCommand
import actron.example.weather.feature.weather.presentation.WeatherAction
import actron.example.weather.feature.weather.presentation.WeatherCommand
import actron.example.weather.feature.weather.presentation.WeatherContext
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.MachineProfiler
import actron.statechart.machine.profileWith
import actron.timetravel.historyFields
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

internal fun WeatherHistory.searchRecording(): MachineRecording<CitySearchState, CitySearchAction, SearchCitiesCommand, NoEvent> = recording(CitySearchState.serializer(), ::searchCheckpoint, ::updateSearch)

internal fun WeatherHistory.weatherRecording(city: City): MachineRecording<WeatherContext, WeatherAction, WeatherCommand, NoEvent> = recording(WeatherContext.serializer(), { weatherCheckpoint(city) }, ::updateWeather)

private fun <C : Any, A : Action, CMD : Any, E : Event> WeatherHistory.recording(
    serializer: KSerializer<C>,
    restore: () -> ExecutorCheckpoint<C, CMD>,
    update: (ExecutorCheckpoint<C, CMD>) -> Unit,
): MachineRecording<C, A, CMD, E> =
    MachineRecording { machine, _, handler ->
        val checkpoint = restore()
        val profiler = MachineProfiler(machine).apply { beginAt(checkpoint.snapshot) }
        val observer = observer(machine, checkpoint, { historyFields(Json.encodeToJsonElement(serializer, it)) }, update, profiler)
        MachineSetup(checkpoint, profiler.commands(handler), clock, listOf(profiler, observer)) { profileWith(profiler) }
    }
