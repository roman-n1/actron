@file:OptIn(actron.core.ExperimentalActronApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.*
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
    val queue = ArrayDeque<List<ScheduleOperation<A>>>()
    queue.add(emptyList())
    val completed = mutableListOf<List<ScheduleOperation<A>>>()
    val failures = mutableListOf<ScheduleFailure<A>>()
    var runs = 0
    var truncated = false
    while (queue.isNotEmpty() && runs < maxRuns && failures.isEmpty()) {
        val path = queue.removeFirst()
        var driver = MachineTestDriver(this, initial, scope)
        val executed = mutableListOf<ScheduleOperation<A>>()
        runs++
        try {
            driver.start()
            driver.setup()
            driver.check()
            if (driver.failures.isNotEmpty()) throw AssertionError("Executor reported ${driver.failures}")
            for (operation in path) {
                require(operation in driver.choices(executed)) { "[Actron] Non-repeatable schedule choices at $executed" }
                executed += operation
                when (operation) {
                    ScheduleOperation.Restart -> {
                        val checkpoint = driver.settle()
                        require(checkpoint.effects.isEmpty() && checkpoint.ending.isEmpty() && driver.recorder.unconsumedEvents.isEmpty()) { "[Actron] Consume effects before restarting the test executor" }
                        driver.close()
                        scope.testScheduler.runCurrent()
                        driver = MachineTestDriver(this, initial, scope, checkpoint = checkpoint)
                        driver.start()
                    }

                    is ScheduleOperation.Send -> {
                        driver.send(operation.action)
                    }

                    is ScheduleOperation.Answer -> {
                        driver.answer(operation.command, operation.action)
                    }

                    is ScheduleOperation.Complete -> {
                        driver.complete(operation.command)
                    }

                    is ScheduleOperation.Fail -> {
                        driver.fail(operation.command)
                    }

                    is ScheduleOperation.Advance -> {
                        driver.advanceBy(operation.duration)
                    }
                }
                driver.check()
                if (driver.failures.isNotEmpty()) throw AssertionError("Executor reported ${driver.failures}")
            }
            val next = driver.choices(executed).distinct()
            if (next.isEmpty()) {
                driver.assertNoPendingWork()
                completed += path
            } else if (path.size >= maxDepth) {
                truncated = true
            } else {
                for (operation in next) {
                    if (queue.size + runs >= maxRuns) {
                        truncated = true
                        break
                    }
                    queue.add(path + operation)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AssertionError) {
            failures += ScheduleFailure(executed.toList(), failure.message.orEmpty())
        } finally {
            driver.close()
            scope.testScheduler.runCurrent()
        }
    }
    return ScheduleReport(runs, completed.toList(), failures.toList(), truncated || (queue.isNotEmpty() && failures.isEmpty()))
}
