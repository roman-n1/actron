package actron.example.weather.feature.weather.presentation

import actron.core.Action
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.weather.domain.HourlyForecast
import actron.example.weather.feature.weather.domain.WeatherAdvice
import actron.example.weather.feature.weather.domain.WeatherReading
import actron.statechart.machine.*
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

@Serializable
data class WeatherContext(
    val city: City,
    val current: RequestState<WeatherReading> = RequestState.Ready,
    val hourly: RequestState<HourlyForecast> = RequestState.Ready,
    val advice: WeatherAdviceState = WeatherAdviceState.Ready,
)

@Serializable
sealed interface WeatherAction : Action {
    @Serializable data object Refresh : WeatherAction

    @Serializable data object RetryForecast : WeatherAction

    @Serializable data object DismissAdvice : WeatherAction

    @Serializable data object RetryAdvice : WeatherAction

    @Serializable data class AdviceLoaded(
        val city: City,
        val items: List<WeatherAdvice>,
    ) : WeatherAction

    @Serializable data class AdviceFailed(
        val reason: String,
    ) : WeatherAction

    @Serializable data class Loaded(
        val reading: WeatherReading,
    ) : WeatherAction

    @Serializable data class Unavailable(
        val reason: String,
    ) : WeatherAction

    @Serializable data class ForecastLoaded(
        val forecast: HourlyForecast,
    ) : WeatherAction

    @Serializable data class ForecastFailed(
        val message: String,
    ) : WeatherAction
}

@Serializable
sealed interface WeatherCommand {
    @Serializable data class Advice(
        val city: City,
    ) : WeatherCommand

    @Serializable data class Current(
        val city: City,
    ) : WeatherCommand

    @Serializable data class Hourly(
        val city: City,
    ) : WeatherCommand
}

/** The screen accepts one Refresh input; all three ready regions decide it in the same step. */
object CityWeatherMachine {
    private data object WeatherScreen

    private data object Current

    private data object Advice

    private data object Hourly

    fun create(): Machine<WeatherContext, WeatherAction, WeatherCommand, NoEvent> =
        typedStateMachine(
            id = DefinitionId(value = "city-weather"),
            version = DefinitionVersion(value = "8"),
            initial = WeatherScreen,
            readState = { _: WeatherContext -> WeatherScreen },
            writeState = { context, _ -> context },
            build = {
                parallel(value = WeatherScreen, build = {
                    currentRegion()
                    hourlyRegion()
                    adviceRegion()
                })
            },
        )

    private fun TypedParallelMachineBuilder<WeatherContext, WeatherScreen, WeatherAction, WeatherCommand, NoEvent>.currentRegion() {
        region<Current, RequestState<WeatherReading>>(
            initial = RequestState.Ready,
            readState = { context: WeatherContext -> context.current },
            // Each writer preserves the other region, including its data and pending state.
            writeState = { context, state: RequestState<WeatherReading> -> context.copy(current = state) },
            build = {
                requestStates(
                    loading = {
                        // Command and timeout belong to this child activation, not to the parent.
                        onEnter(block = {
                            command(
                                command = WeatherCommand.Current(city = context.city),
                                lane = LaneId(value = "current"),
                                policy = ConcurrencyPolicy.Latest,
                            )
                        })
                        on<WeatherAction.Loaded, RequestState.Success<WeatherReading>>(
                            guard = { context, action -> action.reading.city == context.city },
                            to = { _, action -> RequestState.Success(value = action.reading) },
                        )
                        on<WeatherAction.Unavailable, RequestState.Error>(
                            to = { _, action -> RequestState.Error(cause = RequestFailure.Unavailable(message = action.reason)) },
                        )
                    },
                )
            },
        )
    }

    private fun TypedParallelMachineBuilder<WeatherContext, WeatherScreen, WeatherAction, WeatherCommand, NoEvent>.hourlyRegion() {
        region<Hourly, RequestState<HourlyForecast>>(
            initial = RequestState.Ready,
            readState = { context: WeatherContext -> context.hourly },
            writeState = { context, state: RequestState<HourlyForecast> -> context.copy(hourly = state) },
            build = {
                requestStates(
                    inputs = { hourlyInputs() },
                    loading = {
                        onEnter(block = {
                            command(
                                command = WeatherCommand.Hourly(city = context.city),
                                lane = LaneId(value = "hourly"),
                                policy = ConcurrencyPolicy.Latest,
                            )
                        })
                        on<WeatherAction.ForecastLoaded, RequestState.Success<HourlyForecast>>(
                            guard = { context, action -> action.forecast.city == context.city },
                            to = { _, action -> RequestState.Success(value = action.forecast) },
                        )
                        on<WeatherAction.ForecastFailed, RequestState.Error>(
                            to = { _, action -> RequestState.Error(cause = RequestFailure.Unavailable(message = action.message)) },
                        )
                    },
                )
            },
        )
    }

    private fun TypedParallelMachineBuilder<WeatherContext, WeatherScreen, WeatherAction, WeatherCommand, NoEvent>.adviceRegion() {
        region<Advice, WeatherAdviceState>(
            initial = WeatherAdviceState.Ready,
            readState = { context -> context.advice },
            writeState = { context, state -> context.copy(advice = state) },
            build = {
                state(value = WeatherAdviceState.Ready, build = { refreshAdvice() })
                state(value = WeatherAdviceState.Loading, build = {
                    refreshAdvice()
                    onEnter(block = {
                        command(command = WeatherCommand.Advice(city = context.city), lane = LaneId(value = "advice"), policy = ConcurrencyPolicy.Latest)
                    })
                    on<WeatherAction.AdviceLoaded, WeatherAdviceState.Hidden>(
                        guard = { context, action -> action.city == context.city && action.items.isEmpty() },
                        to = { _, _ -> WeatherAdviceState.Hidden },
                    )
                    on<WeatherAction.AdviceLoaded, WeatherAdviceState.Visible>(
                        guard = { context, action -> action.city == context.city && action.items.isNotEmpty() },
                        to = { _, action -> WeatherAdviceState.Visible(items = action.items) },
                    )
                    on<WeatherAction.AdviceFailed, WeatherAdviceState.Error>(
                        to = { _, action -> WeatherAdviceState.Error(cause = RequestFailure.Unavailable(message = action.reason)) },
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
                    on<WeatherAction.RetryAdvice, WeatherAdviceState.Loading>()
                })
            },
        )
    }

    private fun TypedMachineStateBuilder<WeatherContext, WeatherAdviceState, WeatherAction, WeatherCommand, NoEvent>.refreshAdvice() {
        on<WeatherAction.Refresh, WeatherAdviceState.Loading>()
    }

    private fun TypedMachineStateBuilder<WeatherContext, WeatherAdviceState, WeatherAction, WeatherCommand, NoEvent>.dismissAdvice() {
        on<WeatherAction.DismissAdvice, WeatherAdviceState.Dismissed>(to = { _, _ -> WeatherAdviceState.Dismissed })
    }

    // Ready has no effects. One input is broadcast to the active regions; each owns its transition.
    private fun <T : Any> TypedStateMachineBuilder<WeatherContext, RequestState<T>, WeatherAction, WeatherCommand, NoEvent>.requestStates(
        inputs: TypedMachineStateBuilder<WeatherContext, RequestState<T>, WeatherAction, WeatherCommand, NoEvent>.() -> Unit = {},
        loading: TypedMachineStateBuilder<WeatherContext, RequestState<T>, WeatherAction, WeatherCommand, NoEvent>.() -> Unit,
    ) {
        val requestInputs: TypedMachineStateBuilder<WeatherContext, RequestState<T>, WeatherAction, WeatherCommand, NoEvent>.() -> Unit = {
            on<WeatherAction.Refresh, RequestState.Loading>()
            inputs()
        }
        state(value = RequestState.Ready, build = requestInputs)
        state(value = RequestState.Loading, build = {
            requestInputs()
            loading()
            after<RequestState.Error>(delay = 8.seconds, to = { RequestState.Error(cause = RequestFailure.Timeout) })
        })
        state<RequestState.Success<T>>(build = requestInputs)
        state<RequestState.Error>(build = requestInputs)
    }

    private fun TypedMachineStateBuilder<WeatherContext, RequestState<HourlyForecast>, WeatherAction, WeatherCommand, NoEvent>.hourlyInputs() {
        // No matching declaration in Current: retry only replaces the forecast request.
        on<WeatherAction.RetryForecast, RequestState.Loading>()
    }
}
