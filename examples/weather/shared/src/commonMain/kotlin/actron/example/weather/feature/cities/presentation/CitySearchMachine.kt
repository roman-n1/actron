package actron.example.weather.feature.cities.presentation

import actron.core.Action
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.core.mvi.RequestFailure
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

/**
 * Pure screen behavior: edits/results/timers produce a new context and descriptions of work.
 * HTTP runs in citySearchHandler, not in this DSL. See docs/architecture.md for the full flow.
 */
object CitySearchMachine {
    fun create(): Machine<CitySearchState, CitySearchAction, SearchCitiesCommand, NoEvent> =
        typedStateMachine(
            // Stable identity groups instances in the inspector; version protects persisted data.
            id = DefinitionId(value = "city-search"),
            version = DefinitionVersion(value = "10"),
            initial = RequestState.Idle,
            // phase is the single state value; the DSL derives its graph nodes from state<T>.
            readState = { context: CitySearchState -> context.phase },
            writeState = { context, phase: RequestState<List<CityOption>> -> context.copy(phase = phase) },
            build = {
                state(value = RequestState.Idle, build = { queryChanges() })
                state(value = RequestState.QueryChanged, build = {
                    // Resolve the updated query before the executor publishes this decision.
                    always<RequestState.Idle>(guard = { context -> context.query.isBlank() })
                    always<RequestState.Debouncing>(guard = { context -> context.query.isNotBlank() })
                })
                state(value = RequestState.Debouncing, build = {
                    queryChanges()
                    // Leaving/re-entering Debouncing cancels/restarts this activation's timer.
                    after<RequestState.Loading>(delay = 350.milliseconds, to = { RequestState.Loading })
                })
                state<RequestState.Loading>(build = { searchRequest() })
                state<RequestState.Success<List<CityOption>>>(build = { queryChanges() })
                state<RequestState.Error>(build = { queryChanges() })
            },
        )

    private fun TypedMachineStateBuilder<CitySearchState, RequestState<List<CityOption>>, CitySearchAction, SearchCitiesCommand, NoEvent>.searchRequest() {
        queryChanges()
        onEnter(block = {
            // Register data for the executor. Exiting Loading cancels its command immediately.
            command(
                command = SearchCitiesCommand(query = context.query.trim(), revision = context.revision),
                lane = LaneId(value = "city-search"),
                policy = ConcurrencyPolicy.Latest,
            )
        })
        on<CitySearchAction.Found, RequestState.Success<List<CityOption>>>(
            // Defense for old callbacks delivered outside normal command-result identity checks.
            guard = { context, action -> action.revision == context.revision },
            to = { _, action -> RequestState.Success(value = action.results) },
        )
        on<CitySearchAction.Failed, RequestState.Error>(
            guard = { context, action -> action.revision == context.revision },
            to = { _, action -> RequestState.Error(cause = RequestFailure.Unavailable(message = action.message)) },
        )
        after<RequestState.Error>(delay = 8.seconds, to = { RequestState.Error(cause = RequestFailure.Timeout) })
    }

    private fun TypedMachineStateBuilder<CitySearchState, RequestState<List<CityOption>>, CitySearchAction, SearchCitiesCommand, NoEvent>.queryChanges() {
        // Every edit updates query/revision once; QueryChanged owns the automatic choice.
        transition<CitySearchAction.ChangeQuery, RequestState.QueryChanged>(
            reduce = { context, action -> context.copy(query = action.query, revision = context.revision + 1) },
        )
    }
}
