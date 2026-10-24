package actron.core

/** The executable handler contract assembled by the DSL; proposals remain local to each scope. */
internal class StoreHandlers<S : State, A : Action, E : Event>(
    val onEnter: suspend EnterScope<S, E, S>.() -> Unit,
    val onAction: suspend ActionScope<S, A, E, S>.() -> Unit,
    val onExit: suspend ExitScope<S, E, S>.() -> Unit,
    val onError: suspend RecoverScope<S, E, S, Exception>.() -> Unit,
    val validateRecoveredState: (S, S) -> Unit,
)
