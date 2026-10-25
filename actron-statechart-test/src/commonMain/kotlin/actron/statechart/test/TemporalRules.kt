package actron.statechart.test

import actron.core.Action
import actron.core.Event
import kotlin.time.Duration

/** A finite-trace result: outstanding obligations are not silently reported as satisfied. */
data class TemporalFinding(
    val rule: String,
    val triggerStep: Int,
    val observedStep: Int,
    val message: String,
)

data class TemporalReport(
    val violations: List<TemporalFinding>,
    val pending: List<TemporalFinding>,
    val triggers: Map<String, Int>,
) {
    fun assertSatisfied(requireTriggers: Boolean = true) {
        val missingTriggers = requireTriggers && triggers.values.any { it == 0 }
        if (violations.isNotEmpty() || pending.isNotEmpty() || missingTriggers) throw AssertionError("[Actron] Temporal rules: violations=$violations; pending=$pending; triggers=$triggers")
    }
}

/** Evaluates immutable decision traces, using their logical timestamps rather than wall time. */
class TemporalRules<C : Any, A : Action, CMD : Any, E : Event> {
    private val checks = linkedMapOf<String, (List<ScenarioStep<C, A, CMD, E>>) -> TemporalReport>()

    internal val size: Int get() = checks.size

    internal fun frozen(): TemporalRules<C, A, CMD, E> = TemporalRules<C, A, CMD, E>().also { it.checks.putAll(checks) }

    /** A safety property of every observed prefix. This makes no claim about an infinite future. */
    fun always(
        name: String,
        holds: (List<ScenarioStep<C, A, CMD, E>>) -> Boolean,
    ) {
        add(name) { steps ->
            val violations = steps.indices.filter { !holds(steps.take(it + 1)) }.map { TemporalFinding(name, it, it, "Safety rule rejected the prefix") }
            TemporalReport(violations, emptyList(), mapOf(name to steps.size))
        }
    }

    /** Each trigger owns its obligation; correlate request identities in [satisfied]. Same-step answers count. */
    fun respondsWithin(
        name: String,
        within: Duration,
        maxSteps: Int,
        triggered: (ScenarioStep<C, A, CMD, E>) -> Boolean,
        satisfied: (trigger: ScenarioStep<C, A, CMD, E>, later: ScenarioStep<C, A, CMD, E>) -> Boolean,
    ) {
        require(within.isFinite() && within >= Duration.ZERO && maxSteps >= 0)
        val response = ResponseWindow(name, within, maxSteps, triggered, satisfied)
        add(name, response::check)
    }

    private fun add(
        name: String,
        check: (List<ScenarioStep<C, A, CMD, E>>) -> TemporalReport,
    ) {
        require(name.isNotBlank() && name !in checks) { "[Actron] Temporal rule names must be unique and nonblank" }
        checks[name] = check
    }

    fun check(steps: List<ScenarioStep<C, A, CMD, E>>): TemporalReport {
        require(steps.zipWithNext().all { (a, b) -> a.input.now <= b.input.now }) { "[Actron] Trace time moved backwards" }
        val reports = checks.values.map { it(steps) }
        return TemporalReport(reports.flatMap { it.violations }, reports.flatMap { it.pending }, reports.flatMap { it.triggers.entries }.associate { it.toPair() })
    }

    fun check(trace: ScenarioTrace<C, A, CMD, E>): TemporalReport = check(trace.steps)
}
