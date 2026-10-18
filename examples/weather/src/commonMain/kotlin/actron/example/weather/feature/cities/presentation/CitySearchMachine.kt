package actron.example.weather.feature.cities.presentation

import actron.core.Action
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.cities.domain.CityOption
import actron.statechart.machine.*
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Serializable
data class CitySearchState(
    val query: String = "",
    val phase: RequestState<List<CityOption>> = RequestState.Idle,
    val revision: Long = 0,
)

@Serializable
sealed interface CitySearchAction : Action {
    @Serializable
    data class ChangeQuery(
        val query: String,
    ) : CitySearchAction

    @Serializable
    data class Found(
        val revision: Long,
        val results: List<CityOption>,
    ) : CitySearchAction

    @Serializable
    data class Failed(
        val revision: Long,
        val message: String,
    ) : CitySearchAction
}

@Serializable
data class SearchCitiesCommand(
    val query: String,
    val revision: Long,
)

/** Every edit is a decision; only the HTTP command is debounced. Timers and answers are recorded. */
object CitySearchMachine {
    fun create(): Machine<CitySearchState, CitySearchAction, SearchCitiesCommand, NoEvent> =
        typedStateMachine(
            DefinitionId("city-search"),
            DefinitionVersion("8"),
            initial = RequestState.Idle,
            readState = { context: CitySearchState -> context.phase },
            writeState = { context, phase: RequestState<List<CityOption>> -> context.copy(phase = phase) },
        ) {
            state<RequestState.Idle> { queryChanges() }
            state<RequestState.Debouncing> {
                queryChanges()
                after<RequestState.Loading>(350.milliseconds) { RequestState.Loading }
            }
            state<RequestState.Loading> {
                queryChanges()
                onEnter {
                    command(SearchCitiesCommand(context.query.trim(), context.revision), LaneId("city-search"), ConcurrencyPolicy.Latest)
                }
                on<CitySearchAction.Found, RequestState.Success<List<CityOption>>>(
                    guard = { context, action -> action.revision == context.revision },
                ) { _, action -> RequestState.Success(action.results) }
                on<CitySearchAction.Failed, RequestState.Error>(
                    guard = { context, action -> action.revision == context.revision },
                ) { _, action -> RequestState.Error(action.message) }
                after<RequestState.TimedOut>(8.seconds) { RequestState.TimedOut }
            }
            state<RequestState.Success<List<CityOption>>> { queryChanges() }
            state<RequestState.Error> { queryChanges() }
            state<RequestState.TimedOut> { queryChanges() }
        }

    private fun TypedMachineStateBuilder<CitySearchState, RequestState<List<CityOption>>, CitySearchAction, SearchCitiesCommand, NoEvent>.queryChanges() {
        on<CitySearchAction.ChangeQuery, RequestState.Idle>(
            guard = { _, action -> action.query.isBlank() },
            reduce = { context, action -> context.copy(query = action.query, revision = context.revision + 1) },
        ) { _, _ -> RequestState.Idle }
        on<CitySearchAction.ChangeQuery, RequestState.Debouncing>(
            guard = { _, action -> action.query.isNotBlank() },
            reduce = { context, action -> context.copy(query = action.query, revision = context.revision + 1) },
        ) { _, _ -> RequestState.Debouncing }
    }
}
