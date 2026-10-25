package actron.statechart

import actron.core.Action
import actron.core.Event
import kotlinx.coroutines.Job

/** Stages activation changes and launches until the whole automatic macrostep succeeds. */
internal class ChartStoreTransition<C : Any, A : Action, E : Event>(
    state: ChartState<C>,
    private val activities: ChartStoreActivities<C, A, E>,
    private val timers: ChartStoreTimers,
    private val work: ChartStoreWork<C, E>,
    private val schedule: (Int, Long) -> Unit,
) {
    private val previous = state
    var state = state
        private set
    private val working = activities.snapshot()
    private val created = mutableListOf<Job>()
    private val cancelled = mutableListOf<Job>()
    private val launches = mutableListOf<ChartStoreTask<C, E>>()

    fun exit(id: StateId) {
        working.remove(id)?.let(cancelled::add)
    }

    fun enter(id: StateId): Job =
        Job().also {
            created += it
            working[id] = it
        }

    fun launch(task: ChartStoreTask<C, E>) {
        launches += task
    }

    fun update(
        step: StepResult.Transitioned,
        context: C,
    ) {
        val running = state.timers.copy(running = state.timers.running - timers.indicesOf(step.exited).toSet())
        state = ChartState(step.configuration, context, timers.issue(running, timers.indicesOf(step.entered)))
    }

    fun rollback() {
        created.forEach { it.cancel() }
    }

    fun commit(): ChartState<C> {
        val entered = working.filter { (id, job) -> !activities.matches(id, job) }.keys
        cancelled.forEach { it.cancel() }
        activities.replace(working)
        launches.filter { it.activation.isActive }.forEach(work::submit)
        entered.forEach(activities::start)
        state.timers.running
            .filter { (index, token) -> previous.timers.running[index] != token }
            .forEach { (index, token) -> schedule(index, token) }
        return state
    }
}
