@file:OptIn(actron.core.InternalActronApi::class)

package actron.core

/** Frozen executable registrations; selection and introspection use the same entries. */
internal class HandlerRegistry<S : State, A : Action, E : Event>(
    enter: List<StoreBuilder.StateHandler<(S) -> Boolean, EnterScope<S, E, S>>>,
    action: List<StoreBuilder.StateHandler<(S, A) -> Boolean, ActionScope<S, A, E, S>>>,
    exit: List<StoreBuilder.StateHandler<(S) -> Boolean, ExitScope<S, E, S>>>,
    recover: List<StoreBuilder.StateHandler<(S, Exception) -> Boolean, RecoverScope<S, E, S, Exception>>>,
    validators: List<(S, S) -> Unit>,
) {
    private val enterHandlers = enter.toList()
    private val actionHandlers = action.toList()
    private val exitHandlers = exit.toList()
    private val recoveryHandlers = recover.toList()
    private val recoveryValidators = validators.toList()

    // Immutable projections are safe to retain: the executable registrations never change.
    val enter: List<HandlerMatcher> = enterHandlers.map { it.matcher }
    val action: List<HandlerMatcher> = actionHandlers.map { it.matcher }
    val exit: List<HandlerMatcher> = exitHandlers.map { it.matcher }
    val recover: List<HandlerMatcher> = recoveryHandlers.map { it.matcher }
    val actionPredicates: List<(S, A) -> Boolean> = actionHandlers.map { it.predicate }

    fun handlers(): StoreHandlers<S, A, E> =
        StoreHandlers(
            onEnter = { invokeEnter(this) },
            onAction = { invokeAction(this) },
            onExit = { invokeExit(this) },
            onError = { invokeRecovery(this) },
            validateRecoveredState = { before, after -> recoveryValidators.forEach { it(before, after) } },
        )

    private suspend fun invokeEnter(scope: EnterScope<S, E, S>) {
        for (handler in enterHandlers) {
            if (handler.predicate(scope.state)) {
                handler.handler(scope)
                return
            }
        }
    }

    private suspend fun invokeAction(scope: ActionScope<S, A, E, S>) {
        for (handler in actionHandlers) {
            if (handler.predicate(scope.state, scope.action)) {
                handler.handler(scope)
                return
            }
        }
    }

    private suspend fun invokeExit(scope: ExitScope<S, E, S>) {
        for (handler in exitHandlers) {
            if (handler.predicate(scope.state)) {
                handler.handler(scope)
                return
            }
        }
    }

    private suspend fun invokeRecovery(scope: RecoverScope<S, E, S, Exception>) {
        for (handler in recoveryHandlers) {
            if (handler.predicate(scope.state, scope.error)) {
                handler.handler(scope)
                return
            }
        }
        throw scope.error
    }

    fun hasAction(
        state: S,
        input: A,
    ): Boolean = actionHandlers.any { it.predicate(state, input) }

    @OptIn(InternalActronApi::class)
    fun matches(
        state: S,
        input: A,
    ): List<ActionHandlerMatch> =
        actionHandlers.mapIndexed { index, handler ->
            ActionHandlerMatch(index, handler.matcher.stateType, handler.matcher.inputType, handler.predicate(state, input))
        }

    @OptIn(InternalActronApi::class)
    fun metadata(): StoreHandlerMetadata {
        fun List<HandlerMatcher>.toMetadata() = mapIndexed { index, matcher -> HandlerMetadata(index, matcher.stateType, matcher.inputType) }
        return StoreHandlerMetadata(
            enter = enter.toMetadata(),
            action = action.toMetadata(),
            exit = exit.toMetadata(),
            recover = recover.toMetadata(),
        )
    }
}
