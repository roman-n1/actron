package actron.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

/** Builds handler scopes and owns the runtimes of active state variants. Called under the Store mutex. */
internal class StoreStateScopes<S : State, A : Action, E : Event>(
    private val execution: StoreScopeExecution<S, E>,
    private val rootScope: () -> CoroutineScope,
    private val cleanup: (() -> Unit) -> Unit,
) {
    private val stateRuntimes = mutableMapOf<KClass<out S>, StateRuntime>()
    private val launchedScopes = StoreLaunchedScopes(execution)

    fun activeLaunches(): List<Job> = stateRuntimes.values.flatMap { it.activeLaunches() }

    private fun runtime(state: S): StateRuntime = stateRuntimes[state::class] ?: throw StoreBoundaryFailure(IllegalStateException("[Actron] State scope is not found"))

    suspend fun action(
        state: S,
        action: A,
        handler: suspend ActionScope<S, A, E, S>.() -> Unit,
    ): S {
        var newState: S = state
        handler.invoke(
            object : ActionScope<S, A, E, S> {
                override val state = state
                override val action = action

                override fun nextState(block: () -> S) {
                    newState = block()
                }

                override fun clearPendingActions() {
                    execution.clearPendingActions()
                }

                override suspend fun event(event: E) {
                    execution.emit(event)
                }

                override fun cancelLaunch(lane: LaunchLane) {
                    val stateRuntime = runtime(state)
                    stateRuntime.cancelLaunch(lane)
                }

                override fun launch(
                    dispatcher: CoroutineContext,
                    control: LaunchControl,
                    block: suspend ActionLaunchScope<S, A, E, S>.() -> Unit,
                ) {
                    val stateRuntime = runtime(state)
                    stateRuntime.launchAction(action, control) {
                        execution.launch(
                            stateRuntime = stateRuntime,
                            dispatcher = dispatcher,
                            buildLaunchScope = { launchedScopes.action(stateRuntime.scope, action) },
                            block = block,
                        )
                    }
                }

                override fun subscribe(
                    dispatcher: CoroutineContext,
                    block: suspend ActionLaunchScope<S, A, E, S>.() -> Unit,
                ) {
                    val stateRuntime = runtime(state)
                    val job =
                        execution.launch(
                            stateRuntime = stateRuntime,
                            dispatcher = dispatcher,
                            buildLaunchScope = { launchedScopes.action(stateRuntime.scope, action) },
                            block = block,
                        )
                    stateRuntime.subscribe(job)
                }
            },
        )
        return newState
    }

    suspend fun enter(
        state: S,
        handler: suspend EnterScope<S, E, S>.() -> Unit,
    ): S {
        stateRuntimes[state::class]?.scope?.cancel()
        val parent = rootScope()
        val stateRuntime =
            StateRuntime(
                scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job])),
                cleanup = cleanup,
            )
        stateRuntimes[state::class] = stateRuntime
        var newState: S = state
        handler.invoke(
            object : EnterScope<S, E, S> {
                override val state = state

                override fun nextState(block: () -> S) {
                    newState = block()
                }

                override fun clearPendingActions() {
                    execution.clearPendingActions()
                }

                override suspend fun event(event: E) {
                    execution.emit(event)
                }

                override fun launch(
                    dispatcher: CoroutineContext,
                    block: suspend EnterLaunchScope<S, E, S>.() -> Unit,
                ) {
                    execution.launch(
                        stateRuntime = stateRuntime,
                        dispatcher = dispatcher,
                        buildLaunchScope = { launchedScopes.enter(stateRuntime.scope) },
                        block = block,
                    )
                }

                override fun subscribe(
                    dispatcher: CoroutineContext,
                    block: suspend EnterLaunchScope<S, E, S>.() -> Unit,
                ) {
                    val job =
                        execution.launch(
                            stateRuntime = stateRuntime,
                            dispatcher = dispatcher,
                            buildLaunchScope = { launchedScopes.enter(stateRuntime.scope) },
                            block = block,
                        )
                    stateRuntime.subscribe(job)
                }
            },
        )
        return newState
    }

    // A failing exit aborts the transition: retain the old runtime and its launches until exit succeeds.
    suspend fun exit(
        state: S,
        handler: suspend ExitScope<S, E, S>.() -> Unit,
    ) {
        handler.invoke(
            object : ExitScope<S, E, S> {
                override val state = state

                override fun clearPendingActions() {
                    execution.clearPendingActions()
                }

                override suspend fun event(event: E) {
                    execution.emit(event)
                }
            },
        )
        stateRuntimes.remove(state::class)?.scope?.cancel()
    }

    suspend fun recover(
        state: S,
        throwable: Exception,
        handler: suspend RecoverScope<S, E, S, Exception>.() -> Unit,
    ): S {
        var newState: S = state
        handler.invoke(
            object : RecoverScope<S, E, S, Exception> {
                override val state = state
                override val error = throwable

                override fun nextState(block: () -> S) {
                    newState = block()
                }

                override fun clearPendingActions() {
                    execution.clearPendingActions()
                }

                override suspend fun event(event: E) {
                    execution.emit(event)
                }
            },
        )
        return newState
    }
}
