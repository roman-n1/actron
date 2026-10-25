package actron.statechart.test

import actron.core.Action
import actron.core.Event
import kotlin.time.Duration

/** Each trigger is assessed independently against the same finite trace and logical deadline. */
internal class ResponseWindow<C : Any, A : Action, CMD : Any, E : Event>(
    private val name: String,
    private val within: Duration,
    private val maxSteps: Int,
    private val triggered: (ScenarioStep<C, A, CMD, E>) -> Boolean,
    private val satisfied: (ScenarioStep<C, A, CMD, E>, ScenarioStep<C, A, CMD, E>) -> Boolean,
) {
    private sealed interface Outcome {
        data object Satisfied : Outcome

        data class Violated(
            val finding: TemporalFinding,
        ) : Outcome

        data class Pending(
            val finding: TemporalFinding,
        ) : Outcome
    }

    fun check(steps: List<ScenarioStep<C, A, CMD, E>>): TemporalReport {
        val violations = mutableListOf<TemporalFinding>()
        val pending = mutableListOf<TemporalFinding>()
        val triggers = steps.indices.filter { triggered(steps[it]) }
        for (start in triggers) {
            when (val outcome = assess(steps, start)) {
                Outcome.Satisfied -> Unit
                is Outcome.Violated -> violations += outcome.finding
                is Outcome.Pending -> pending += outcome.finding
            }
        }
        return TemporalReport(violations, pending, mapOf(name to triggers.size))
    }

    private fun assess(
        steps: List<ScenarioStep<C, A, CMD, E>>,
        start: Int,
    ): Outcome {
        val trigger = steps[start]
        for (index in start..steps.lastIndex) {
            val current = steps[index]
            val elapsed = current.input.now - trigger.input.now
            if (elapsed > within || index - start > maxSteps) {
                return Outcome.Violated(TemporalFinding(name, start, index, "Response deadline exceeded"))
            }
            if (satisfied(trigger, current)) return Outcome.Satisfied
            if (index - start == maxSteps) {
                return Outcome.Violated(TemporalFinding(name, start, index, "No response at the response deadline"))
            }
        }
        return Outcome.Pending(TemporalFinding(name, start, steps.lastIndex, "Trace ended before this obligation was resolved"))
    }
}
