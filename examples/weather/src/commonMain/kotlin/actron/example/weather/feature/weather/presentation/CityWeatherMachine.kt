package actron.example.weather.feature.weather.presentation

import actron.core.Action
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.weather.domain.HourlyForecast
import actron.example.weather.feature.weather.domain.WeatherReading
import actron.statechart.machine.*
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

@Serializable
data class WeatherContext(
    val city: City,
    val current: RequestState<WeatherReading> = RequestState.Idle,
    val hourly: RequestState<HourlyForecast> = RequestState.Idle,
)

@Serializable
sealed interface WeatherAction : Action {
    @Serializable data object Refresh : WeatherAction

    @Serializable data object RetryForecast : WeatherAction

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
    @Serializable data class Current(
        val city: City,
    ) : WeatherCommand

    @Serializable data class Hourly(
        val city: City,
    ) : WeatherCommand
}

object CityWeatherMachine {
    fun create(): Machine<WeatherContext, WeatherAction, WeatherCommand, NoEvent> =
        parallelStateMachine(
            DefinitionId("city-weather"),
            DefinitionVersion("5"),
        ) {
            region(
                "Current",
                initial = RequestState.Idle,
                readState = { context: WeatherContext -> context.current },
                writeState = { context, state: RequestState<WeatherReading> -> context.copy(current = state) },
            ) {
                state<RequestState.Idle> { currentInputs() }
                state<RequestState.Loading> {
                    currentInputs()
                    onEnter { command(WeatherCommand.Current(context.city), LaneId("current"), ConcurrencyPolicy.Latest) }
                    on<WeatherAction.Loaded, RequestState.Success<WeatherReading>>(
                        guard = { context, action -> action.reading.city == context.city },
                    ) { _, action -> RequestState.Success(action.reading) }
                    on<WeatherAction.Unavailable, RequestState.Error> { _, action -> RequestState.Error(action.reason) }
                    after<RequestState.TimedOut>(8.seconds) { RequestState.TimedOut }
                }
                state<RequestState.Success<WeatherReading>> { currentInputs() }
                state<RequestState.Error> { currentInputs() }
                state<RequestState.TimedOut> { currentInputs() }
            }
            region(
                "Hourly",
                initial = RequestState.Idle,
                readState = { context: WeatherContext -> context.hourly },
                writeState = { context, state: RequestState<HourlyForecast> -> context.copy(hourly = state) },
            ) {
                state<RequestState.Idle> { hourlyInputs() }
                state<RequestState.Loading> {
                    hourlyInputs()
                    onEnter { command(WeatherCommand.Hourly(context.city), LaneId("hourly"), ConcurrencyPolicy.Latest) }
                    on<WeatherAction.ForecastLoaded, RequestState.Success<HourlyForecast>>(
                        guard = { context, action -> action.forecast.city == context.city },
                    ) { _, action -> RequestState.Success(action.forecast) }
                    on<WeatherAction.ForecastFailed, RequestState.Error> { _, action -> RequestState.Error(action.message) }
                    after<RequestState.TimedOut>(8.seconds) { RequestState.TimedOut }
                }
                state<RequestState.Success<HourlyForecast>> { hourlyInputs() }
                state<RequestState.Error> { hourlyInputs() }
                state<RequestState.TimedOut> { hourlyInputs() }
            }
        }

    private fun TypedMachineStateBuilder<WeatherContext, RequestState<WeatherReading>, WeatherAction, WeatherCommand, NoEvent>.currentInputs() {
        on<WeatherAction.Refresh, RequestState.Loading> { _, _ -> RequestState.Loading }
    }

    private fun TypedMachineStateBuilder<WeatherContext, RequestState<HourlyForecast>, WeatherAction, WeatherCommand, NoEvent>.hourlyInputs() {
        on<WeatherAction.Refresh, RequestState.Loading> { _, _ -> RequestState.Loading }
        on<WeatherAction.RetryForecast, RequestState.Loading> { _, _ -> RequestState.Loading }
    }
}
