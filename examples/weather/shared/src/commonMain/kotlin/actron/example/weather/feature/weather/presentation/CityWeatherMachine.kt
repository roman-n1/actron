package actron.example.weather.feature.weather.presentation

import actron.core.Action
import actron.core.Event
import actron.example.weather.core.domain.City
import actron.statechart.machine.*
import kotlinx.serialization.Serializable

@Serializable
data class WeatherScreenState(
    val city: City,
)

@Serializable
sealed interface WeatherScreenAction : Action {
    @Serializable data object Refresh : WeatherScreenAction

    @Serializable data object RetryForecast : WeatherScreenAction

    @Serializable data object RetryAdvice : WeatherScreenAction

    @Serializable data object DismissAdvice : WeatherScreenAction

    @Serializable data object Back : WeatherScreenAction
}

@Serializable
sealed interface WeatherScreenEvent : Event {
    @Serializable data object RefreshChildren : WeatherScreenEvent

    @Serializable data object RetryForecastRequested : WeatherScreenEvent

    @Serializable data object RetryAdviceRequested : WeatherScreenEvent

    @Serializable data object DismissAdviceRequested : WeatherScreenEvent

    @Serializable data object BackRequested : WeatherScreenEvent
}

/** The screen owns its header and requests updates; it never reads a child's internal state. */
object CityWeatherMachine {
    fun create(): ScreenMachine<WeatherScreenState, WeatherScreenAction, WeatherScreenEvent> =
        screenMachine(
            id = DefinitionId(value = "city-weather"),
            version = DefinitionVersion(value = "13"),
            initialType = WeatherScreenState::class,
        ) {
            state<WeatherScreenState> {
                onEvent<WeatherScreenAction.RetryForecast> { _, _ -> WeatherScreenEvent.RetryForecastRequested }
                onEvent<WeatherScreenAction.RetryAdvice> { _, _ -> WeatherScreenEvent.RetryAdviceRequested }
                onEvent<WeatherScreenAction.DismissAdvice> { _, _ -> WeatherScreenEvent.DismissAdviceRequested }
                onEvent<WeatherScreenAction.Back> { _, _ -> WeatherScreenEvent.BackRequested }
                onEnter { event(WeatherScreenEvent.RefreshChildren) }
                on<WeatherScreenAction.Refresh> { target { state } }
            }
        }
}
