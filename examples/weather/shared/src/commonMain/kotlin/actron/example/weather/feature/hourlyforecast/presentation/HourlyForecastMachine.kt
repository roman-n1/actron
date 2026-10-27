package actron.example.weather.feature.hourlyforecast.presentation

import actron.core.Action
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.weather.domain.HourlyForecast
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.Machine
import actron.statechart.machine.TypedMachineStateBuilder
import actron.statechart.machine.typedStateMachine
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

@Serializable
data class HourlyForecastContext(
    val city: City,
    val state: RequestState<HourlyForecast> = RequestState.Ready,
)

@Serializable
sealed interface HourlyForecastAction : Action {
    @Serializable data object Refresh : HourlyForecastAction

    @Serializable data object Retry : HourlyForecastAction

    @Serializable data class Loaded(
        val forecast: HourlyForecast,
    ) : HourlyForecastAction

    @Serializable data class Failed(
        val message: String,
    ) : HourlyForecastAction
}

@Serializable
data class LoadHourlyForecast(
    val city: City,
)

/** Reusable feature machine: no screen or sibling action is part of its contract. */
object HourlyForecastMachine {
    fun create(): Machine<HourlyForecastContext, HourlyForecastAction, LoadHourlyForecast, NoEvent> =
        typedStateMachine(
            id = DefinitionId(value = "hourly-forecast"),
            version = DefinitionVersion(value = "1"),
            initial = RequestState.Ready,
            readState = { context: HourlyForecastContext -> context.state },
            writeState = { context, state -> context.copy(state = state) },
            build = {
                val refresh: TypedMachineStateBuilder<HourlyForecastContext, RequestState<HourlyForecast>, HourlyForecastAction, LoadHourlyForecast, NoEvent>.() -> Unit = {
                    on<HourlyForecastAction.Refresh, RequestState.Loading>()
                    on<HourlyForecastAction.Retry, RequestState.Loading>()
                }
                state(value = RequestState.Ready, build = refresh)
                state(value = RequestState.Loading, build = {
                    refresh()
                    onEnter(block = { command(command = LoadHourlyForecast(city = context.city)) })
                    on<HourlyForecastAction.Loaded, RequestState.Success<HourlyForecast>>(
                        guard = { context, action -> action.forecast.city == context.city },
                        to = { _, action -> RequestState.Success(value = action.forecast) },
                    )
                    on<HourlyForecastAction.Failed, RequestState.Error>(
                        to = { _, action -> RequestState.Error(cause = RequestFailure.Unavailable(message = action.message)) },
                    )
                    after<RequestState.Error>(delay = 8.seconds, to = { RequestState.Error(cause = RequestFailure.Timeout) })
                })
                state<RequestState.Success<HourlyForecast>>(build = refresh)
                state<RequestState.Error>(build = refresh)
            },
        )
}
