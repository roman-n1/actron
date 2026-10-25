package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.observability.FailureDescriptor
import actron.statechart.StateId
import actron.statechart.machine.Decision
import actron.statechart.machine.DecisionOutcome
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.InvariantViolation
import actron.statechart.machine.InvariantViolationException
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.MachineTime
import actron.statechart.machine.TransitionId

/** Supplies typed payloads and command answers; a deterministic function of snapshot and time. */
fun interface MachineInputGenerator<C : Any, A : Action> {
    fun inputs(
        snapshot: MachineSnapshot<C>,
        now: MachineTime,
    ): List<MachineInput<A>>
}

/** Adds timers in deadline/id order. They fire at their deadline, or now when overdue. */
fun <C : Any, A : Action> MachineInputGenerator<C, A>.withTimers(): MachineInputGenerator<C, A> =
    MachineInputGenerator { snapshot, now ->
        inputs(snapshot, now) +
            snapshot.timers.entries
                .sortedWith(compareBy({ it.value.deadline }, { it.key.value }))
                .map { (id, timer) -> MachineInput.TimerFired(id, maxOf(now, timer.deadline)) }
    }

/** Search strategies; both stop at the same explicit depth/decision limits. */
sealed interface ExplorationStrategy {
    /** Enumerates input sequences breadth first without merging different contexts or histories. */
    data object BreadthFirst : ExplorationStrategy

    /** Breadth-first search stopping once every declared transition was actually taken. */
    data object AllTransitions : ExplorationStrategy

    /** Stops at a finite coverage target; missing obligations remain visible in the report. */
    data class Cover(
        val target: CoverageTarget,
    ) : ExplorationStrategy

    /** Reproducible random walks through the application's generated input choices. */
    data class RandomWalk(
        val seed: Int,
        val runs: Int = 100,
    ) : ExplorationStrategy {
        init {
            require(runs > 0) { "[Actron] Random walks need at least one run" }
        }
    }
}

/** Coverage uses declared nodes/transitions; unreachable declarations remain visible as missing. */
data class CoverageMetric<T : Any>(
    val expected: Set<T>,
    val covered: Set<T>,
) {
    val missing: Set<T> get() = expected - covered
    val percent: Double get() = if (expected.isEmpty()) 100.0 else 100.0 * (covered intersect expected).size / expected.size
}

/** A guard branch of a particular transition, rather than just a shared guard label. */
data class GuardOutcome(
    val transition: TransitionId,
    val result: Boolean,
)

/** Coverage from actual decisions, including timer transitions and guard rejections. */
data class MachineCoverage(
    val states: CoverageMetric<StateId>,
    val transitions: CoverageMetric<TransitionId>,
    val guards: CoverageMetric<GuardOutcome>,
    val timers: CoverageMetric<TransitionId>,
    val definition: DefinitionId,
    val version: DefinitionVersion,
)

/** Actual problems observed while assessing a prefix. Enforcement retains rollback identities. */
sealed interface SequenceProblem {
    val identities: Set<String>
    val violations: List<InvariantViolation>

    data class Invariants(
        override val violations: List<InvariantViolation>,
    ) : SequenceProblem {
        override val identities: Set<String> get() = violations.mapTo(linkedSetOf()) { it.identity }
    }

    data class Execution(
        val failure: FailureDescriptor,
    ) : SequenceProblem {
        override val identities: Set<String> get() = setOf("decision:${failure.typeLabel}")
        override val violations: List<InvariantViolation> = emptyList()
    }
}

/** The first failing executable prefix, with the stable snapshot and actual observed problems. */
data class SequenceFailure<C : Any, A : Action>(
    val inputs: List<MachineInput<A>>,
    val snapshot: MachineSnapshot<C>,
    val problems: List<SequenceProblem>,
) {
    val violations: List<InvariantViolation> get() = problems.flatMap { it.violations }

    /** Stable failure identities exclude payloads and exception messages. */
    val identities: Set<String> get() = problems.flatMapTo(linkedSetOf()) { it.identities }
}

/** An executable generated scenario with actual typed payloads and virtual times. */
data class MachineScenario<A : Action>(
    val name: String,
    val inputs: List<MachineInput<A>>,
)

/** Coverage of an executable prefix, captured during exploration rather than inferred from its graph. */
data class ScenarioCoverage<A : Action>(
    val scenario: MachineScenario<A>,
    val transitions: Set<TransitionId>,
)

/** Actual prefix observations, separate from the original transition-only record's stable ABI. */
data class BehaviouralScenarioCoverage<A : Action>(
    val scenario: MachineScenario<A>,
    val coverage: MachineCoverage,
)

data class BehaviouralExplorationReport<C : Any, A : Action>(
    val report: ExplorationReport<C, A>,
    val scenarios: List<BehaviouralScenarioCoverage<A>>,
)

/** Search results. [truncated] means a decision/failure budget stopped work, not proof of safety. */
data class ExplorationReport<C : Any, A : Action>(
    val decisions: Int,
    val invariantsChecked: Long,
    val coverage: MachineCoverage,
    val failures: List<SequenceFailure<C, A>>,
    val truncated: Boolean,
    val maxDepth: Int,
    val scenarios: List<MachineScenario<A>> = emptyList(),
    val coverageScenarios: List<ScenarioCoverage<A>> = emptyList(),
) {
    /** An assertion with the failing prefix; shrink it with [Machine.shrink] before saving a regression. */
    fun assertSuccess() {
        if (failures.isNotEmpty()) {
            val failure = failures.first()
            throw AssertionError("[Actron] Sequence failed: ${failure.identities}\nInputs: ${failure.inputs}\nSnapshot: ${failure.snapshot}")
        }
        if (truncated) throw AssertionError("[Actron] Exploration budget exhausted; coverage is incomplete")
    }
}

internal fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.sequenceFailure(
    inputs: List<MachineInput<A>>,
    decision: Decision<C, CMD, E>,
    accept: (SequenceFailure<C, A>) -> Unit,
): Boolean {
    val outcome = decision.outcome
    val problems =
        buildList {
            if (outcome is DecisionOutcome.Failed) {
                val cause = outcome.cause
                if (cause is InvariantViolationException) {
                    // Enforcement has rolled back the invalid snapshot but retains the rejected identities.
                    add(SequenceProblem.Invariants(cause.violations))
                    return@buildList
                }
            }
            val violations = checkInvariants(decision.snapshot)
            if (violations.isNotEmpty()) add(SequenceProblem.Invariants(violations))
            if (outcome is DecisionOutcome.Failed) add(SequenceProblem.Execution(outcome.failure))
        }
    if (problems.isEmpty()) return false
    accept(SequenceFailure(inputs.toList(), decision.snapshot, problems))
    return true
}

/**
 * Executes model-based scenarios without IO. Starts an unstarted snapshot once, then explores
 * supplied typed inputs (use [withTimers] for timer choices). Each prefix is checked, including
 * ignored actions and action handlers. Paths are not merged by active configuration.
 *
 * This is a bounded search, not a proof for arbitrary depth or arbitrary action payloads.
 * Generators must be pure and return finite choices whose times do not go backwards.
 */
fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.explore(
    initial: MachineSnapshot<C>,
    generator: MachineInputGenerator<C, A>,
    strategy: ExplorationStrategy = ExplorationStrategy.BreadthFirst,
    maxDepth: Int = 10,
    maxDecisions: Int = 10_000,
    maxFailures: Int = 1,
    now: MachineTime = MachineTime.Zero,
): ExplorationReport<C, A> = exploreDetailed(initial, generator, strategy, maxDepth, maxDecisions, maxFailures, now).report

/** Per-prefix state/guard observations captured during the same decisions, without replaying metadata. */
fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.exploreDetailed(
    initial: MachineSnapshot<C>,
    generator: MachineInputGenerator<C, A>,
    strategy: ExplorationStrategy = ExplorationStrategy.BreadthFirst,
    maxDepth: Int = 10,
    maxDecisions: Int = 10_000,
    maxFailures: Int = 1,
    now: MachineTime = MachineTime.Zero,
): BehaviouralExplorationReport<C, A> = explorePaths(initial, { snapshot, time, _ -> generator.inputs(snapshot, time) }, strategy, maxDepth, maxDecisions, maxFailures, now)

internal fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.explorePaths(
    initial: MachineSnapshot<C>,
    generator: (MachineSnapshot<C>, MachineTime, List<ScenarioStep<C, A, CMD, E>>) -> List<MachineInput<A>>,
    strategy: ExplorationStrategy,
    maxDepth: Int,
    maxDecisions: Int,
    maxFailures: Int,
    now: MachineTime,
    retainSteps: Boolean = false,
    checksPerStep: Int = 0,
    inspect: (List<ScenarioStep<C, A, CMD, E>>) -> List<InvariantViolation> = { emptyList() },
    goalsMet: () -> Boolean = { true },
): BehaviouralExplorationReport<C, A> {
    val limits = ExplorationLimits(maxDepth, maxDecisions, maxFailures)
    val hooks = ExplorationHooks(generator, retainSteps, checksPerStep, inspect, goalsMet)
    return ModelExplorer(this, strategy, limits, hooks).run(initial, now)
}
