package actron.example.weather.feature.currentweather.presentation

import actron.core.Action
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.weather.domain.WeatherReading
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.Machine
import actron.statechart.machine.TypedMachineStateBuilder
import actron.statechart.machine.typedStateMachine
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

@Serializable
data class CurrentWeatherContext(
    val city: City,
    val state: RequestState<WeatherReading> = RequestState.Ready,
)

@Serializable
sealed interface CurrentWeatherAction : Action {
    @Serializable data object Refresh : CurrentWeatherAction

    @Serializable data class Loaded(
        val reading: WeatherReading,
    ) : CurrentWeatherAction

    @Serializable data class Failed(
        val message: String,
    ) : CurrentWeatherAction
}

@Serializable
data class LoadCurrentWeather(
    val city: City,
)

/** Reusable feature machine: no screen or sibling action is part of its contract. */
object CurrentWeatherMachine {
    fun create(): Machine<CurrentWeatherContext, CurrentWeatherAction, LoadCurrentWeather, NoEvent> =
        typedStateMachine(
            id = DefinitionId(value = "current-weather"),
            version = DefinitionVersion(value = "1"),
            initial = RequestState.Ready,
            readState = { context: CurrentWeatherContext -> context.state },
            writeState = { context, state -> context.copy(state = state) },
            build = {
                val refresh: TypedMachineStateBuilder<CurrentWeatherContext, RequestState<WeatherReading>, CurrentWeatherAction, LoadCurrentWeather, NoEvent>.() -> Unit = {
                    on<CurrentWeatherAction.Refresh, RequestState.Loading>()
                }
                state(value = RequestState.Ready, build = refresh)
                state(value = RequestState.Loading, build = {
                    refresh()
                    onEnter(block = { command(command = LoadCurrentWeather(city = context.city)) })
                    on<CurrentWeatherAction.Loaded, RequestState.Success<WeatherReading>>(
                        guard = { context, action -> action.reading.city == context.city },
                        to = { _, action -> RequestState.Success(value = action.reading) },
                    )
                    on<CurrentWeatherAction.Failed, RequestState.Error>(
                        to = { _, action -> RequestState.Error(cause = RequestFailure.Unavailable(message = action.message)) },
                    )
                    after<RequestState.Error>(delay = 8.seconds, to = { RequestState.Error(cause = RequestFailure.Timeout) })
                })
                state<RequestState.Success<WeatherReading>>(build = refresh)
                state<RequestState.Error>(build = refresh)
            },
        )
}
