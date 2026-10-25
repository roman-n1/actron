@file:OptIn(actron.core.ExperimentalActronApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.Machine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

internal sealed interface SchedulePrefix<A : Action> {
    data class Failed<A : Action>(
        val failure: ScheduleFailure<A>,
    ) : SchedulePrefix<A>

    data class Explored<A : Action>(
        val choices: List<ScheduleOperation<A>>,
    ) : SchedulePrefix<A>
}

/** Owns the real executor for one replay, including replacement at Restart and cleanup on failure. */
internal class ScheduleExecution<C : Any, A : Action, CMD : Any, E : Event>(
    private val machine: Machine<C, A, CMD, E>,
    private val initial: C,
    private val scope: TestScope,
) {
    private var driver = MachineTestDriver(machine, initial, scope)
    private val executed = mutableListOf<ScheduleOperation<A>>()

    suspend fun run(
        path: List<ScheduleOperation<A>>,
        setup: suspend MachineTestDriver<C, A, CMD, E>.() -> Unit,
        choices: MachineTestDriver<C, A, CMD, E>.(List<ScheduleOperation<A>>) -> List<ScheduleOperation<A>>,
        check: MachineTestDriver<C, A, CMD, E>.() -> Unit,
    ): SchedulePrefix<A> =
        try {
            driver.start()
            driver.setup()
            verify(check)
            for (operation in path) {
                require(operation in driver.choices(executed)) { "[Actron] Non-repeatable schedule choices at $executed" }
                executed += operation
                apply(operation)
                verify(check)
            }
            val next = driver.choices(executed).distinct()
            if (next.isEmpty()) driver.assertNoPendingWork()
            SchedulePrefix.Explored(next)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AssertionError) {
            SchedulePrefix.Failed(ScheduleFailure(executed.toList(), failure.message.orEmpty()))
        } finally {
            driver.close()
            scope.testScheduler.runCurrent()
        }

    private fun verify(check: MachineTestDriver<C, A, CMD, E>.() -> Unit) {
        driver.check()
        if (driver.failures.isNotEmpty()) throw AssertionError("Executor reported ${driver.failures}")
    }

    private suspend fun apply(operation: ScheduleOperation<A>) {
        when (operation) {
            ScheduleOperation.Restart -> restart()
            is ScheduleOperation.Send -> driver.send(operation.action)
            is ScheduleOperation.Answer -> driver.answer(operation.command, operation.action)
            is ScheduleOperation.Complete -> driver.complete(operation.command)
            is ScheduleOperation.Fail -> driver.fail(operation.command)
            is ScheduleOperation.Advance -> driver.advanceBy(operation.duration)
        }
    }

    private suspend fun restart() {
        val checkpoint = driver.settle()
        require(checkpoint.effects.isEmpty() && checkpoint.ending.isEmpty() && driver.recorder.unconsumedEvents.isEmpty()) { "[Actron] Consume effects before restarting the test executor" }
        driver.close()
        scope.testScheduler.runCurrent()
        driver = MachineTestDriver(machine, initial, scope, checkpoint = checkpoint)
        driver.start()
    }
}
