package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.InvariantViolation
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.MachineTime
import kotlin.random.Random

internal class ExplorationLimits(
    val depth: Int,
    val decisions: Int,
    val failures: Int,
) {
    init {
        require(depth >= 0 && decisions > 0 && failures > 0) { "[Actron] Invalid exploration limits" }
    }
}

internal class ExplorationHooks<C : Any, A : Action, CMD : Any, E : Event>(
    val inputs: (MachineSnapshot<C>, MachineTime, List<ScenarioStep<C, A, CMD, E>>) -> List<MachineInput<A>>,
    val retainSteps: Boolean,
    val checksPerStep: Int,
    val inspect: (List<ScenarioStep<C, A, CMD, E>>) -> List<InvariantViolation>,
    val goalsMet: () -> Boolean,
)

/** Owns one bounded search: budgets, aggregate observations and prefix scheduling. */
internal class ModelExplorer<C : Any, A : Action, CMD : Any, E : Event>(
    private val machine: Machine<C, A, CMD, E>,
    private val strategy: ExplorationStrategy,
    private val limits: ExplorationLimits,
    private val hooks: ExplorationHooks<C, A, CMD, E>,
) {
    private val coverage = MachineCoverageRecorder(machine)
    private val failures = mutableListOf<SequenceFailure<C, A>>()
    private val scenarios = linkedMapOf<MachineCoverage, MachineScenario<A>>()
    private var decisions = 0
    private var checked = 0L
    private var truncated = false

    fun run(
        initial: MachineSnapshot<C>,
        now: MachineTime,
    ): BehaviouralExplorationReport<C, A> {
        require(initial.definition == machine.id && initial.version == machine.version) { "[Actron] Snapshot belongs to another machine or version" }
        val empty = coverage.snapshot()
        val path = ExplorationPath<C, A, CMD, E>(initial, emptyList(), now, 0, empty.copy(states = empty.states.copy(covered = initial.configuration.active)))
        start(path) { started ->
            when (val selected = strategy) {
                is ExplorationStrategy.RandomWalk -> randomWalk(started, selected)
                else -> breadthFirst(started)
            }
        }
        return report()
    }

    private fun start(
        path: ExplorationPath<C, A, CMD, E>,
        explore: (ExplorationPath<C, A, CMD, E>) -> Unit,
    ) {
        if (!path.snapshot.isStarted) {
            var started = path
            if (decide(path, MachineInput.Start(path.now), 0) { started = it }) explore(started)
            return
        }
        coverage.observeInitial(path.snapshot)
        scenarios[path.coverage] = MachineScenario("initial", emptyList())
        checked += machine.invariants.size
        val violations = machine.checkInvariants(path.snapshot)
        if (violations.isEmpty()) {
            explore(path)
        } else {
            failures += SequenceFailure(emptyList(), path.snapshot, listOf(SequenceProblem.Invariants(violations)))
        }
    }

    private fun decide(
        path: ExplorationPath<C, A, CMD, E>,
        input: MachineInput<A>,
        depth: Int,
        advance: (ExplorationPath<C, A, CMD, E>) -> Unit,
    ): Boolean {
        if (decisions >= limits.decisions || failures.size >= limits.failures) {
            truncated = true
            return false
        }
        require(input.now >= path.now) { "[Actron] Generator moved virtual time backwards" }
        val explained = machine.decideExplained(path.snapshot, input)
        decisions++
        coverage.accept(explained)
        checked += machine.invariants.size + hooks.checksPerStep
        val next = path.advance(input, explained, depth, hooks.retainSteps)
        recordScenario(next)
        val violations = hooks.inspect(next.steps)
        val problems = mutableListOf<SequenceProblem>()
        machine.sequenceFailure(next.inputs, explained.decision) { problems += it.problems }
        if (violations.isNotEmpty()) problems += SequenceProblem.Invariants(violations)
        if (problems.isNotEmpty()) {
            failures += SequenceFailure(next.inputs, next.snapshot, problems)
            return false
        }
        advance(next)
        return true
    }

    private fun recordScenario(path: ExplorationPath<C, A, CMD, E>) {
        if (path.coverage !in scenarios) {
            scenarios[path.coverage] = MachineScenario("scenario-${scenarios.size + 1}", path.inputs.toList())
        } else {
            val previous = scenarios.getValue(path.coverage)
            if (path.inputs.size < previous.inputs.size) scenarios[path.coverage] = previous.copy(inputs = path.inputs.toList())
        }
    }

    private val goalReached: () -> Boolean =
        when (strategy) {
            ExplorationStrategy.AllTransitions -> {
                val requirements = machine.chart.requirements(CoverageTarget.AllTransitions)

                fun(): Boolean = requirements.missing(coverage.snapshot()).isEmpty
            }

            is ExplorationStrategy.Cover -> {
                val requirements = machine.chart.requirements(strategy.target)

                fun(): Boolean = requirements.missing(coverage.snapshot()).isEmpty
            }

            else -> {
                { false }
            }
        }

    private fun goalMet(): Boolean = goalReached() && hooks.goalsMet()

    private fun breadthFirst(start: ExplorationPath<C, A, CMD, E>) {
        val queue = ArrayDeque<ExplorationPath<C, A, CMD, E>>()
        queue.addLast(start)
        while (queue.isNotEmpty() && !truncated) {
            if (goalMet()) break
            val path = queue.removeFirst()
            if (path.depth == limits.depth) continue
            for (input in hooks.inputs(path.snapshot, path.now, path.steps)) {
                decide(path, input, path.depth + 1, queue::addLast)
                if (truncated || goalMet()) break
            }
        }
    }

    private fun randomWalk(
        start: ExplorationPath<C, A, CMD, E>,
        strategy: ExplorationStrategy.RandomWalk,
    ) {
        val random = Random(strategy.seed)
        repeat(strategy.runs) {
            var path = start
            var advancing = true
            while (advancing && path.depth < limits.depth && !truncated) {
                val choices = hooks.inputs(path.snapshot, path.now, path.steps)
                if (choices.isEmpty()) break
                advancing = decide(path, choices[random.nextInt(choices.size)], path.depth + 1) { path = it }
            }
        }
    }

    private fun report(): BehaviouralExplorationReport<C, A> {
        val report =
            ExplorationReport(
                decisions,
                checked,
                coverage.snapshot(),
                failures.toList(),
                truncated,
                limits.depth,
                scenarios.values.toList(),
                scenarios.map { (observations, scenario) -> ScenarioCoverage(scenario, observations.transitions.covered) },
            )
        return BehaviouralExplorationReport(report, scenarios.map { (observations, scenario) -> BehaviouralScenarioCoverage(scenario, observations) })
    }
}
