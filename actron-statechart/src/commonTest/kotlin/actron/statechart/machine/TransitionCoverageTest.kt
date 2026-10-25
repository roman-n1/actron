package actron.statechart.machine

import actron.core.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransitionCoverageTest {
    private data object Tick : Action

    private data class Counter(
        val value: Int = 0,
    )

    private val machine =
        typedStateMachine<Counter, Tick, Nothing, Nothing>(DefinitionId("coverage"), DefinitionVersion("1"), Counter()) {
            state<Counter> { internal<Tick> { state, _ -> state.copy(value = state.value + 1) } }
        }

    @Test
    fun repeatedAndParallelTransitionsCountIndividuallyWithoutMutatingHistory() {
        val initial = TransitionCoverage(machine.id, machine.version)
        val start = machine.decide(machine.initialSnapshot(Counter()), MachineInput.Start(MachineTime.Zero))
        val decision = machine.decide(start.snapshot, MachineInput.Dispatch(Tick, MachineTime.Zero))
        val first = initial.record(decision)
        assertEquals(1, first.hits.size)
        val repeated = first.record(decision.copy(transitions = decision.transitions + TransitionId(1)))
        assertEquals(2L, repeated.hits[decision.transitions.single()])
        assertEquals(1L, repeated.hits[TransitionId(1)])
        assertEquals(1L, first.hits.values.single())
        assertTrue(initial.hits.isEmpty())
        assertEquals(repeated, repeated.record(decision.copy(outcome = DecisionOutcome.Ignored(IgnoreReason.NotStarted))))
    }

    @Test
    fun mismatchedVersionAndInvalidCountsAreRejectedAndCountersSaturate() {
        val start = machine.decide(machine.initialSnapshot(Counter()), MachineInput.Start(MachineTime.Zero))
        val decision = machine.decide(start.snapshot, MachineInput.Dispatch(Tick, MachineTime.Zero))
        assertFailsWith<IllegalArgumentException> { TransitionCoverage(machine.id, DefinitionVersion("other")).record(decision) }
        assertFailsWith<IllegalArgumentException> { TransitionCoverage(machine.id, machine.version, mapOf(TransitionId(0) to -1L)) }
        val saturated = TransitionCoverage(machine.id, machine.version, mapOf(decision.transitions.single() to Long.MAX_VALUE))
        assertEquals(saturated, saturated.record(decision))
    }
}
