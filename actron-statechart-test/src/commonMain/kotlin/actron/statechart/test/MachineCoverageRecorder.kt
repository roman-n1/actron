package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.core.InputAttribution
import actron.core.InputId
import actron.statechart.HistoryState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.machine.Decision
import actron.statechart.machine.DecisionExplanation
import actron.statechart.machine.DecisionTraceObserver
import actron.statechart.machine.ExplainedDecision
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.MachineStore
import actron.statechart.machine.TransitionId
import kotlinx.coroutines.sync.Mutex

/**
 * Bounded behavioural coverage from actual MachineStore decisions. Attach as an observer of this
 * machine only, or use the test driver's built-in recorder. No guards are evaluated by the recorder.
 * Reports are safe to read across threads; only model ids and guard outcomes are retained.
 */
class MachineCoverageRecorder<C : Any, A : Action, CMD : Any, E : Event>(
    private val machine: Machine<C, A, CMD, E>,
) : DecisionTraceObserver<C, A, CMD, E> {
    private val lock = Mutex()
    private val expectedStates =
        machine.chart.states
            .filter { it !is HistoryState }
            .mapTo(linkedSetOf()) { it.id }
    private val expectedTransitions =
        machine.chart.transitions.indices
            .mapTo(linkedSetOf(), ::TransitionId)
    private val expectedTimers =
        machine.chart.transitions
            .withIndex()
            .filter { it.value.isTimer }
            .mapTo(linkedSetOf()) { TransitionId(it.index) }
    private val expectedGuards =
        machine.chart.transitions
            .withIndex()
            .filter { it.value.guard != actron.statechart.GuardCondition.Unconditional }
            .flatMap {
                listOf(GuardOutcome(TransitionId(it.index), true), GuardOutcome(TransitionId(it.index), false))
            }.toSet()
    private val states = linkedSetOf<StateId>()
    private val transitions = linkedSetOf<TransitionId>()
    private val guards = linkedSetOf<GuardOutcome>()

    override fun onDecided(
        input: InputAttribution,
        machineInput: MachineInput<A>,
        explained: ExplainedDecision<C, CMD, E>,
    ) = accept(explained)

    override fun onCommitted(
        input: InputAttribution,
        machineInput: MachineInput<A>,
        decision: Decision<C, CMD, E>,
    ) = locked {
        validate(decision.snapshot)
        states += decision.snapshot.configuration.active
        states += decision.entered.map { it.node }
        transitions += decision.transitions
    }

    internal fun accept(explained: ExplainedDecision<C, CMD, E>) =
        locked {
            validate(explained.decision.snapshot)
            observe(explained.explanation)
            states += explained.decision.snapshot.configuration.active
            states += explained.decision.entered.map { it.node }
            transitions += explained.decision.transitions
        }

    internal fun observeInitial(snapshot: MachineSnapshot<C>) =
        locked {
            validate(snapshot)
            states += snapshot.configuration.active
        }

    private fun validate(snapshot: MachineSnapshot<C>) {
        require(snapshot.definition == machine.id && snapshot.version == machine.version) { "[Actron] Coverage belongs to another machine or version" }
    }

    private fun observe(explanation: DecisionExplanation) {
        states += explanation.active
        for (guard in explanation.guards) {
            when (guard.result) {
                actron.statechart.GuardCheck.Allowed -> guards += GuardOutcome(guard.transition, true)
                actron.statechart.GuardCheck.Rejected -> guards += GuardOutcome(guard.transition, false)
                else -> Unit
            }
        }
    }

    /** Immutable snapshot; unreachable states and untested guard branches remain missing. */
    fun snapshot(): MachineCoverage =
        locked {
            MachineCoverage(
                CoverageMetric(expectedStates.toSet(), states.toSet()),
                CoverageMetric(expectedTransitions.toSet(), transitions.toSet()),
                CoverageMetric(expectedGuards, guards.toSet()),
                CoverageMetric(expectedTimers.toSet(), transitions intersect expectedTimers),
                machine.id,
                machine.version,
            )
        }

    private inline fun <T : Any> locked(block: () -> T): T {
        while (!lock.tryLock()) { /* Only bounded metadata updates hold this lock. */ }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
