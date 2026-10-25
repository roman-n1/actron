package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.core.InputAttribution
import actron.core.InputId
import actron.statechart.HistoryState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.machine.TransitionId
import kotlinx.coroutines.sync.Mutex

/** Combines identical declared universes and identity/version metadata. Definitions must version executable rule changes. */
fun MachineCoverage.merge(other: MachineCoverage): MachineCoverage {
    require(definition == other.definition && version == other.version) { "[Actron] Coverage machine identity/version differs" }

    fun <T : Any> combine(
        first: CoverageMetric<T>,
        second: CoverageMetric<T>,
    ): CoverageMetric<T> {
        require(first.expected == second.expected) { "[Actron] Coverage declarations differ" }
        return CoverageMetric(first.expected, first.covered + second.covered)
    }
    return MachineCoverage(combine(states, other.states), combine(transitions, other.transitions), combine(guards, other.guards), combine(timers, other.timers), definition, version)
}

/** Fails on untested requested transitions; it never treats a false guard as a taken transition. */
fun MachineCoverage.assertTransitionsCovered(required: Set<TransitionId> = transitions.expected) {
    require(required.all { it in transitions.expected }) { "[Actron] Requested an undeclared transition" }
    val missing = required - transitions.covered
    if (missing.isNotEmpty()) throw AssertionError("[Actron] Uncovered transitions: ${missing.sortedBy { it.index }}")
}

/** Metadata-only counts and missing-transition rows suitable for a test report or CI artifact. */
fun MachineCoverage.describe(): String = describeRows { _, _ -> }

fun MachineCoverage.describe(chart: StateChartDefinition): String =
    describeRows { id, text ->
        if (id.index in chart.transitions.indices) {
            val transition = chart.transitions[id.index]
            text
                .append(": ")
                .append(transition.source.value)
                .append(" -> ")
                .append(transition.target.value)
            transition.withActionMatcher { text.append(" on ").append(it.name) }
            transition.guard.withLabel { text.append(" [").append(it).append(']') }
        }
    }

private fun MachineCoverage.describeRows(annotate: (TransitionId, StringBuilder) -> Unit): String =
    buildString {
        fun <T : Any> metric(
            label: String,
            metric: CoverageMetric<T>,
        ) {
            append(label)
                .append(": ")
                .append((metric.covered intersect metric.expected).size)
                .append(" / ")
                .append(metric.expected.size)
                .append(" = ")
                .append(metric.percent)
                .append("%\n")
        }
        metric("States", states)
        metric("Transitions", transitions)
        metric("Guard outcomes", guards)
        metric("Timers", timers)
        for (id in transitions.missing.sortedBy { it.index }) {
            append(id).append(" NOT COVERED")
            annotate(id, this)
            append('\n')
        }
    }
