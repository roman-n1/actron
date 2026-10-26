package actron.example.weather.feature.weather.presentation

import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.feature.weather.domain.WeatherAdvice
import kotlinx.serialization.Serializable

/** This child owns its request, interpreted advice and dismissal, independently of the other cards. */
@Serializable
sealed interface WeatherAdviceState {
    @Serializable data object Loading : WeatherAdviceState

    @Serializable data object Hidden : WeatherAdviceState

    @Serializable data class Visible(
        val items: List<WeatherAdvice>,
    ) : WeatherAdviceState

    @Serializable data object Dismissed : WeatherAdviceState

    @Serializable data class Error(
        val cause: RequestFailure,
    ) : WeatherAdviceState
}
