package actron.example.weather.feature.weather.presentation

import kotlinx.serialization.Serializable

/** The screen parent owns startup and shared chrome; Active owns all three child activations. */
@Serializable
sealed interface WeatherScreenState {
    @Serializable data object Idle : WeatherScreenState

    @Serializable data object Active : WeatherScreenState
}

@Serializable
internal data object WeatherScreen : WeatherScreenState
