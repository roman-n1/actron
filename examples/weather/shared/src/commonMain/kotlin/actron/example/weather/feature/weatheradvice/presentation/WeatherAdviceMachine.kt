package actron.example.weather.feature.weatheradvice.presentation

import actron.core.Action
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.feature.weather.domain.WeatherAdvice
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceState
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.Machine
import actron.statechart.machine.TypedMachineStateBuilder
import actron.statechart.machine.typedStateMachine
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

@Serializable
data class WeatherAdviceContext(
    val city: City,
    val state: WeatherAdviceState = WeatherAdviceState.Ready,
)

@Serializable
sealed interface WeatherAdviceAction : Action {
    @Serializable data object Refresh : WeatherAdviceAction

    @Serializable data object Retry : WeatherAdviceAction

    @Serializable data object Dismiss : WeatherAdviceAction

    @Serializable data class Loaded(
        val city: City,
        val items: List<WeatherAdvice>,
    ) : WeatherAdviceAction

    @Serializable data class Failed(
        val message: String,
    ) : WeatherAdviceAction
}

@Serializable
data class LoadWeatherAdvice(
    val city: City,
)

object WeatherAdviceMachine {
    fun create(): Machine<WeatherAdviceContext, WeatherAdviceAction, LoadWeatherAdvice, NoEvent> =
        typedStateMachine(
            id = DefinitionId(value = "weather-advice"),
            version = DefinitionVersion(value = "1"),
            initial = WeatherAdviceState.Ready,
            readState = { context: WeatherAdviceContext -> context.state },
            writeState = { context, state -> context.copy(state = state) },
            build = {
                state(value = WeatherAdviceState.Ready, build = { refreshAdvice() })
                state(value = WeatherAdviceState.Loading, build = {
                    refreshAdvice()
                    onEnter(block = { command(command = LoadWeatherAdvice(city = context.city)) })
                    on<WeatherAdviceAction.Loaded, WeatherAdviceState.Hidden>(
                        guard = { context, action -> action.city == context.city && action.items.isEmpty() },
                        to = { _, _ -> WeatherAdviceState.Hidden },
                    )
                    on<WeatherAdviceAction.Loaded, WeatherAdviceState.Visible>(
                        guard = { context, action -> action.city == context.city && action.items.isNotEmpty() },
                        to = { _, action -> WeatherAdviceState.Visible(items = action.items) },
                    )
                    on<WeatherAdviceAction.Failed, WeatherAdviceState.Error>(
                        to = { _, action -> WeatherAdviceState.Error(cause = RequestFailure.Unavailable(message = action.message)) },
                    )
                    after<WeatherAdviceState.Error>(delay = 8.seconds, to = { WeatherAdviceState.Error(cause = RequestFailure.Timeout) })
                    dismissAdvice()
                })
                state<WeatherAdviceState.Hidden>(build = { refreshAdvice() })
                state<WeatherAdviceState.Visible>(build = {
                    refreshAdvice()
                    dismissAdvice()
                })
                state<WeatherAdviceState.Dismissed>(build = { refreshAdvice() })
                state<WeatherAdviceState.Error>(build = {
                    refreshAdvice()
                    dismissAdvice()
                    on<WeatherAdviceAction.Retry, WeatherAdviceState.Loading>()
                })
            },
        )

    private fun TypedMachineStateBuilder<WeatherAdviceContext, WeatherAdviceState, WeatherAdviceAction, LoadWeatherAdvice, NoEvent>.refreshAdvice() {
        on<WeatherAdviceAction.Refresh, WeatherAdviceState.Loading>()
    }

    private fun TypedMachineStateBuilder<WeatherAdviceContext, WeatherAdviceState, WeatherAdviceAction, LoadWeatherAdvice, NoEvent>.dismissAdvice() {
        on<WeatherAdviceAction.Dismiss, WeatherAdviceState.Dismissed>(to = { _, _ -> WeatherAdviceState.Dismissed })
    }
}
