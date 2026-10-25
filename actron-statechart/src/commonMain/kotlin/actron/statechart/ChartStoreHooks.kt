package actron.statechart

import actron.core.Action
import actron.core.Event
import actron.core.StoreScope
import kotlinx.coroutines.Job

/** Executes frozen hook/effect registrations in ephemeral DSL scopes under the core lock. */
internal class ChartStoreHooks<C : Any, A : Action, E : Event>(
    private val bindings: ChartStoreBindings<C, A, E>,
    private val taskFor: (StateId, Job, suspend ChartLaunchScope<C, A, E>.() -> Unit) -> ChartStoreTask<C, E>,
) {
    suspend fun apply(
        transition: ChartStoreTransition<C, A, E>,
        step: StepResult.Transitioned,
        action: Action,
        storeScope: StoreScope,
        emit: suspend (E) -> Unit,
    ) {
        var context = transition.state.context
        for (id in step.exited) {
            for (hook in bindings.exitHooks[id].orEmpty()) {
                val scope = ExitScope(id, action, context, storeScope, emit)
                scope.hook()
                context = scope.context
            }
            transition.exit(id)
        }
        for (taken in step.transitions) taken.effect.withLabel { context = bindings.effects.getValue(it)(context, action) }
        for (id in step.entered) context = enter(id, transition.enter(id), context, action, transition::launch, storeScope, emit)
        transition.update(step, context)
    }

    suspend fun enter(
        id: StateId,
        activation: Job,
        context: C,
        action: Action,
        launches: (ChartStoreTask<C, E>) -> Unit,
        storeScope: StoreScope,
        emit: suspend (E) -> Unit,
    ): C {
        var current = context
        for (hook in bindings.enterHooks[id].orEmpty()) {
            val scope = EnterHookScope(id, action, current, storeScope, emit, activation, launches)
            try {
                scope.hook()
            } finally {
                scope.open = false
            }
            current = scope.context
        }
        return current
    }

    private inner class ExitScope(
        override val node: StateId,
        override val action: Action,
        override var context: C,
        override val store: StoreScope,
        private val emit: suspend (E) -> Unit,
    ) : ChartHookScope<C, E> {
        override suspend fun event(event: E) = emit(event)
    }

    private inner class EnterHookScope(
        override val node: StateId,
        override val action: Action,
        override var context: C,
        override val store: StoreScope,
        private val emit: suspend (E) -> Unit,
        private val activation: Job,
        private val launches: (ChartStoreTask<C, E>) -> Unit,
    ) : ChartEnterScope<C, A, E> {
        var open = true

        override suspend fun event(event: E) = emit(event)

        override fun launch(block: suspend ChartLaunchScope<C, A, E>.() -> Unit) {
            check(open) { "[Actron] launch {} was called after the onEnter hook of $node returned; call it from the hook itself" }
            launches(taskFor(node, activation, block))
        }
    }
}
