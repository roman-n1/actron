package actron.statechart

import actron.core.Action
import actron.core.Event
import actron.core.StoreScope
import kotlinx.coroutines.Job

/** Owns committed activations and activity scopes; transition staging holds tentative jobs. */
internal class ChartStoreActivities<C : Any, A : Action, E : Event>(
    private val blocks: Map<StateId, List<suspend ChartLaunchScope<C, A, E>.() -> Unit>>,
    private val submit: (ChartStoreTask<C, E>) -> Unit,
    private val dispatch: (A, () -> Boolean) -> Unit,
) {
    private val active = mutableMapOf<StateId, Job>()

    fun snapshot(): MutableMap<StateId, Job> = active.toMutableMap()

    fun activationOf(id: StateId): Job = active.getValue(id)

    fun matches(
        id: StateId,
        job: Job,
    ): Boolean = active[id] === job

    fun contains(job: Job): Boolean = job in active.values

    fun initialize(entered: Map<StateId, Job>) {
        active.putAll(entered)
    }

    fun replace(next: Map<StateId, Job>) {
        active.clear()
        active.putAll(next)
    }

    fun start(id: StateId) {
        val activation = activationOf(id)
        for (block in blocks[id].orEmpty()) submit(taskFor(id, activation, block))
    }

    fun taskFor(
        id: StateId,
        activation: Job,
        block: suspend ChartLaunchScope<C, A, E>.() -> Unit,
    ): ChartStoreTask<C, E> = ChartStoreTask(activation) { LaunchScope(id, activation, this).block() }

    private inner class LaunchScope(
        override val node: StateId,
        private val activation: Job,
        private val worker: ChartStoreWorker<C, E>,
    ) : ChartLaunchScope<C, A, E> {
        override val store: StoreScope get() = worker.transactor

        // The plain activation Job is cancelled on exit; close cancels the transactor's scope.
        override val isActive: Boolean get() = activation.isActive && worker.transactor.isActive

        override suspend fun event(event: E) {
            if (isActive) worker.transactor.event(event)
        }

        override suspend fun updateContext(transform: (C) -> C): Boolean {
            var applied = false
            val activation = activation
            worker.transactor.transaction {
                if (activation.isActive) {
                    // A throwing transform must not report a successful update.
                    val updated = transform(state.context)
                    applied = true
                    nextState { state.copy(context = updated) }
                }
            }
            return applied
        }

        override fun dispatch(action: A) {
            this@ChartStoreActivities.dispatch(action) { activation.isActive }
        }
    }
}
