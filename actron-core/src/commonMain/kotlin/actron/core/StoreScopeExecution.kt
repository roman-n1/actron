package actron.core

import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

/** Store operations available to DSL scopes; scheduling and the transition mutex stay with the Store. */
internal interface StoreScopeExecution<S : State, E : Event> {
    suspend fun emit(event: E)

    fun clearPendingActions()

    fun <LS : Any> launch(
        stateRuntime: StateRuntime,
        dispatcher: CoroutineContext,
        buildLaunchScope: suspend () -> LS,
        block: suspend LS.() -> Unit,
    ): Job

    // Evaluates validity and reads the snapshot under the same lock as the eventual commit.
    suspend fun transaction(
        dispatcher: CoroutineContext,
        canRun: () -> Boolean,
        block: suspend (S) -> S,
    )
}
