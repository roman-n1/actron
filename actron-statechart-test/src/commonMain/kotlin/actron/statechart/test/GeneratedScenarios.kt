package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.observability.FailureDescriptor
import actron.statechart.machine.ExplainedDecision
import actron.statechart.machine.InvariantViolation
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.MachineTime
import actron.statechart.machine.check

/** One actual pure-model decision. Contexts/actions must be immutable to retain historical values. */
data class ScenarioStep<C : Any, A : Action, CMD : Any, E : Event>(
    val before: MachineSnapshot<C>,
    val input: MachineInput<A>,
    val explained: ExplainedDecision<C, CMD, E>,
) {
    val after: MachineSnapshot<C> get() = explained.decision.snapshot
}

/** Active results use their real command identity. LateAction separately tests application guards. */
enum class ReplyMode { Active, LateCommand, LateAction }

data class ScenarioTrace<C : Any, A : Action, CMD : Any, E : Event>(
    val scenario: MachineScenario<A>,
    val initial: MachineSnapshot<C>,
    val steps: List<ScenarioStep<C, A, CMD, E>>,
    val coverage: MachineCoverage,
    val failures: List<SequenceFailure<C, A>>,
) {
    fun assertSuccess() {
        if (failures.isNotEmpty()) throw AssertionError("[Actron] Scenario failed: ${failures.first().identities}\nInputs: ${failures.first().inputs}")
    }
}

/** Bounded discovery; missing goals/coverage and exhausted budgets never count as success. */
data class GeneratedScenarios<C : Any, A : Action>(
    val exploration: ExplorationReport<C, A>,
    val scenarios: List<MachineScenario<A>>,
    val witnesses: Map<String, MachineScenario<A>>,
    val missingGoals: Set<String>,
    val requirements: CoverageRequirements,
) {
    fun assertSuccess() {
        exploration.assertSuccess()
        val missing = requirements.missing(exploration.coverage)
        if (!missing.isEmpty || missingGoals.isNotEmpty()) {
            throw AssertionError("[Actron] Unreached within depth ${exploration.maxDepth}: coverage=$missing; goals=$missingGoals")
        }
    }
}

internal class ScenarioRule<C : Any, A : Action, CMD : Any, E : Event>(
    val name: String,
    val predicate: (ScenarioStep<C, A, CMD, E>) -> Boolean,
) {
    fun check(step: ScenarioStep<C, A, CMD, E>): List<InvariantViolation> =
        try {
            if (predicate(step)) emptyList() else listOf(InvariantViolation.Rejected(name))
        } catch (error: AssertionError) {
            listOf(InvariantViolation.Failed(name, FailureDescriptor.of(error)))
        } catch (error: Exception) {
            listOf(InvariantViolation.Failed(name, FailureDescriptor.of(error)))
        }
}

/** Finite, deterministic data domains. Callbacks describe choices, never call HTTP/database services. */
class ScenarioModelBuilder<C : Any, A : Action, CMD : Any, E : Event> internal constructor() {
    internal val actions = mutableListOf<(MachineSnapshot<C>) -> List<A>>()
    internal val replies = mutableListOf<Pair<Set<ReplyMode>, (CMD) -> List<A>>>()
    internal val rules = mutableListOf<ScenarioRule<C, A, CMD, E>>()
    internal val temporal = mutableListOf<TemporalRules<C, A, CMD, E>>()
    internal val goals = linkedMapOf<String, (List<ScenarioStep<C, A, CMD, E>>) -> Boolean>()
    internal val extras = mutableListOf<(MachineSnapshot<C>, MachineTime, List<ScenarioStep<C, A, CMD, E>>) -> List<MachineInput<A>>>()

    fun actions(vararg values: A) {
        actions { values.toList() }
    }

    /** Only actions declared at the current active nodes become graph exploration choices. */
    fun actions(values: (MachineSnapshot<C>) -> List<A>) {
        actions += values
    }

    /** Results are derived from commands actually emitted along this path, including their payloads. */
    fun replies(
        modes: Set<ReplyMode> = setOf(ReplyMode.Active),
        values: (CMD) -> List<A>,
    ) {
        require(modes.isNotEmpty())
        replies += modes.toSet() to values
    }

    /** Runs after every decision, including ignored actions and stale results. */
    fun rule(
        name: String,
        predicate: (ScenarioStep<C, A, CMD, E>) -> Boolean,
    ) {
        require(name.isNotBlank() && rules.none { it.name == name }) { "[Actron] Rule names must be unique and nonblank" }
        rules += ScenarioRule(name, predicate)
    }

    /** Checks violated temporal obligations during exploration. Use rules.check(trace) to reject pending obligations at the end. */
    fun temporal(rules: TemporalRules<C, A, CMD, E>) {
        temporal += rules
    }

    /** Requires an actual executable witness; useful when transition coverage alone misses a race. */
    fun goal(
        name: String,
        reached: (List<ScenarioStep<C, A, CMD, E>>) -> Boolean,
    ) {
        require(name.isNotBlank() && name !in goals) { "[Actron] Goal names must be unique and nonblank" }
        goals[name] = reached
    }

    /** Advanced sources/boundary-time choices; preserves typed inputs and actual command/timer IDs. */
    fun inputs(values: (MachineSnapshot<C>, MachineTime, List<ScenarioStep<C, A, CMD, E>>) -> List<MachineInput<A>>) {
        extras += values
    }
}

fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.scenarios(
    initial: C,
    configure: ScenarioModelBuilder<C, A, CMD, E>.() -> Unit,
): ScenarioModel<C, A, CMD, E> = ScenarioModel(this, initial, ScenarioModelBuilder<C, A, CMD, E>().apply(configure))

/** A convenient front end for the existing graph explorer, not a second machine interpreter. */
class ScenarioModel<C : Any, A : Action, CMD : Any, E : Event> internal constructor(
    val machine: Machine<C, A, CMD, E>,
    val initial: C,
    builder: ScenarioModelBuilder<C, A, CMD, E>,
) {
    private val inputChoices = ScenarioInputs(machine, builder.actions.toList(), builder.replies.toList(), builder.extras.toList())
    private val rules = builder.rules.toList()
    private val temporal = builder.temporal.map { it.frozen() }
    private val goals = builder.goals.toMap()

    fun generate(
        target: CoverageTarget = CoverageTarget.AllTransitions,
        maxDepth: Int = 8,
        maxDecisions: Int = 10_000,
    ): GeneratedScenarios<C, A> {
        val witnesses = linkedMapOf<String, MachineScenario<A>>()
        val detailed =
            machine.explorePaths(
                machine.initialSnapshot(initial),
                inputChoices::choices,
                ExplorationStrategy.Cover(target),
                maxDepth,
                maxDecisions,
                1,
                MachineTime.Zero,
                retainSteps = true,
                checksPerStep = rules.size + temporal.sumOf { it.size },
                inspect = { steps ->
                    val violations = rules.flatMap { it.check(steps.last()) } + temporalViolations(steps)
                    if (violations.isEmpty()) {
                        for ((name, reached) in goals) {
                            if (name !in witnesses && reached(steps)) witnesses[name] = MachineScenario(name, steps.map { it.input })
                        }
                    }
                    violations
                },
                goalsMet = { witnesses.size == goals.size },
            )
        val requirements = machine.chart.requirements(target)
        val reached = requirements.items() - requirements.missing(detailed.report.coverage).items()
        val candidates = detailed.scenarios
        val sets = candidates.map { reached - requirements.missing(it.coverage).items() }
        val selection = selectCoverage(sets, candidates.map { it.scenario.inputs.size }, reached, 10_000)
        val selected = selection.indices.map { candidates[it].scenario } + witnesses.values
        val scenarios = if (selected.isEmpty()) detailed.report.scenarios.take(1) else selected.distinctBy { it.inputs }
        return GeneratedScenarios(detailed.report, scenarios, witnesses.toMap(), goals.keys - witnesses.keys, requirements)
    }

    /** Re-executes a generated prefix without IO and records every decision, including rejected inputs. */
    fun record(scenario: MachineScenario<A>): ScenarioTrace<C, A, CMD, E> {
        val start = machine.initialSnapshot(initial)
        val steps = mutableListOf<ScenarioStep<C, A, CMD, E>>()
        val failures = mutableListOf<SequenceFailure<C, A>>()
        val coverage = MachineCoverageRecorder(machine)
        var snapshot = start
        var now = MachineTime.Zero
        require(scenario.inputs.firstOrNull() is MachineInput.Start) { "[Actron] Recorded scenario must begin with Start" }
        for (input in scenario.inputs) {
            require(input.now >= now) { "[Actron] Scenario moved virtual time backwards" }
            now = input.now
            val explained = machine.decideExplained(snapshot, input)
            val step = ScenarioStep(snapshot, input, explained)
            steps += step
            coverage.accept(explained)
            snapshot = step.after
            val problems = mutableListOf<SequenceProblem>()
            machine.sequenceFailure(steps.map { it.input }, explained.decision) { problems += it.problems }
            val violations = rules.flatMap { it.check(step) } + temporalViolations(steps)
            if (violations.isNotEmpty()) problems += SequenceProblem.Invariants(violations)
            if (problems.isNotEmpty()) {
                failures += SequenceFailure(steps.map { it.input }, snapshot, problems)
                break
            }
        }
        return ScenarioTrace(scenario, start, steps.toList(), coverage.snapshot(), failures.toList())
    }

    private fun temporalViolations(steps: List<ScenarioStep<C, A, CMD, E>>): List<InvariantViolation> = temporal.flatMap { it.check(steps).violations }.map { InvariantViolation.Rejected("temporal:${it.rule}@${it.triggerStep}") }
}
