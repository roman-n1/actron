package actron.example.weather.core.mvi

import kotlinx.serialization.Serializable

/** Shared request lifecycle. Each machine declares only the states it uses. */
@Serializable
sealed interface RequestState<out T : Any> {
    @Serializable
    data object Idle : RequestState<Nothing>

    @Serializable
    data object Debouncing : RequestState<Nothing>

    @Serializable
    data object Loading : RequestState<Nothing>

    @Serializable
    data class Success<T : Any>(
        val value: T,
    ) : RequestState<T>

    @Serializable
    data class Error(
        val message: String,
    ) : RequestState<Nothing>

    @Serializable
    data object TimedOut : RequestState<Nothing>
}
