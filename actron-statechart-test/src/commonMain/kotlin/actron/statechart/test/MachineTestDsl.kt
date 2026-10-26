package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.StateId
import actron.statechart.machine.Machine
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration

/** Runs a real queued Store with scripted IO and virtual time; always closes it, including on assertion failure. */
suspend fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.test(
    scope: TestScope,
    initial: C,
    allowPendingWork: Boolean = false,
    scenario: suspend MachineTestScenario<C, A, CMD, E>.() -> Unit,
): MachineCoverage {
    val driver = MachineTestDriver(this, initial, scope)
    try {
        driver.start()
        MachineTestScenario(driver).scenario()
        if (driver.failures.isNotEmpty()) throw AssertionError("[Actron] Unhandled runtime failures: ${driver.failures}")
        if (!allowPendingWork) driver.assertNoPendingWork()
        return driver.coverage
    } finally {
        driver.close()
    }
}

/** Assertions inspect settled snapshots, never arbitrary sleeps or a second evaluation of guards. */
class MachineTestScenario<C : Any, A : Action, CMD : Any, E : Event> internal constructor(
    val driver: MachineTestDriver<C, A, CMD, E>,
) {
    val context: C get() = driver.snapshot.context
    val coverage: MachineCoverage get() = driver.coverage

    suspend fun dispatch(action: A) {
        driver.send(action)
    }

    suspend fun advanceTimeBy(duration: Duration) {
        require(!duration.isNegative()) { "Virtual time cannot move backwards" }
        driver.advanceBy(duration)
    }

    fun expectContext(expected: C) = driver.assertContext(expected)

    fun expectActive(states: Set<StateId>) = driver.assertActive(states)

    /** Select the typed state property for context-based machines; identity works for direct-state machines. */
    inline fun <reified S : Any> expectState(select: (C) -> Any = { it }): S {
        val actual = select(context)
        if (actual !is S) throw AssertionError("[Actron] Expected state ${S::class.simpleName}, got $actual")
        return actual
    }

    /** Exactly one command must match; ambiguity fails before sending any result. */
    suspend fun completeCommand(
        matching: (CMD) -> Boolean = { true },
        result: (CMD) -> A,
    ) {
        val matches = driver.runningCommands.filter { matching(it.command) }
        if (matches.size != 1) throw AssertionError("[Actron] Expected one matching running command, found ${matches.size}: ${matches.map { it.command }}")
        val command = matches.single()
        driver.answer(command.id, result(command.command))
        // A result may exit the command's scope and cancel it already.
        if (driver.runningCommands.any { it.id == command.id }) driver.complete(command.id)
    }
}
