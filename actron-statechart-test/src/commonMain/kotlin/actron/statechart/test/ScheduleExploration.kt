@file:OptIn(actron.core.ExperimentalActronApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.CommandId
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineStore
import actron.statechart.machine.check
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration

/** Operations on the real queued executor. No production HTTP/database handler is invoked. */
sealed interface ScheduleOperation<out A : Action> {
    data object Restart : ScheduleOperation<Nothing>

    data class Send<A : Action>(
        val action: A,
    ) : ScheduleOperation<A>

    data class Answer<A : Action>(
        val command: CommandId,
        val action: A,
    ) : ScheduleOperation<A>

    data class Complete(
        val command: CommandId,
    ) : ScheduleOperation<Nothing>

    data class Fail(
        val command: CommandId,
    ) : ScheduleOperation<Nothing>

    data class Advance(
        val duration: Duration,
    ) : ScheduleOperation<Nothing> {
        init {
            require(duration.isFinite() && duration >= Duration.ZERO)
        }
    }
}

data class ScheduleFailure<A : Action>(
    val operations: List<ScheduleOperation<A>>,
    val message: String,
)

data class ScheduleReport<A : Action>(
    val executions: Int,
    val completed: List<List<ScheduleOperation<A>>>,
    val failures: List<ScheduleFailure<A>>,
    val truncated: Boolean,
) {
    fun assertSuccess() {
        if (failures.isNotEmpty() || truncated) throw AssertionError("[Actron] Executor schedules: failures=$failures; truncated=$truncated")
    }
}

/**
 * Breadth-first exploration with a fresh real MachineStore per prefix. Each operation settles;
 * this explores delivery order, cancellation and timer boundaries, not OS instruction interleavings.
 * [setup] and [choices] must be deterministic. The caller supplies finite choices and bounds.
 * Each fresh run starts logical time at zero; Restart preserves checkpoint time and pending work.
 * The path passed to choices lets callers bound repeated refreshes/restarts without mutating context.
 */
suspend fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.exploreSchedules(
    initial: C,
    scope: TestScope,
    maxDepth: Int = 6,
    maxRuns: Int = 100,
    setup: suspend MachineTestDriver<C, A, CMD, E>.() -> Unit = {},
    choices: MachineTestDriver<C, A, CMD, E>.(List<ScheduleOperation<A>>) -> List<ScheduleOperation<A>>,
    check: MachineTestDriver<C, A, CMD, E>.() -> Unit,
): ScheduleReport<A> {
    require(maxDepth >= 0 && maxRuns > 0)
    val frontier = ScheduleFrontier<A>(maxDepth, maxRuns)
    val failures = mutableListOf<ScheduleFailure<A>>()
    while (frontier.canRun && failures.isEmpty()) {
        val path = frontier.next()
        when (val result = ScheduleExecution(this, initial, scope).run(path, setup, choices, check)) {
            is SchedulePrefix.Failed -> failures += result.failure
            is SchedulePrefix.Explored -> frontier.record(path, result.choices)
        }
    }
    return frontier.report(failures)
}
