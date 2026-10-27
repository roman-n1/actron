package actron.example.weather.feature.weather.presentation

import actron.core.Action
import actron.core.Event
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoCommand
import actron.statechart.machine.*
import kotlinx.serialization.Serializable

@Serializable
data class WeatherScreenContext(
    val city: City,
)

@Serializable
sealed interface WeatherScreenAction : Action {
    @Serializable data object Refresh : WeatherScreenAction
}

@Serializable
sealed interface WeatherScreenEvent : Event {
    @Serializable data object RefreshChildren : WeatherScreenEvent
}

/** The screen owns its header and requests updates; it never reads a child's internal state. */
object CityWeatherMachine {
    private data object Active

    fun create(): Machine<WeatherScreenContext, WeatherScreenAction, NoCommand, WeatherScreenEvent> =
        typedStateMachine(
            id = DefinitionId(value = "city-weather"),
            version = DefinitionVersion(value = "9"),
            initial = Active,
            readState = { _: WeatherScreenContext -> Active },
            writeState = { context, _ -> context },
            build = {
                state(value = Active, build = {
                    onEnter(block = { event(event = WeatherScreenEvent.RefreshChildren) })
                    on<WeatherScreenAction.Refresh, Active>()
                })
            },
        )
}
