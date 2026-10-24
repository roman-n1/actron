package actron.timetravel.compose

import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.machine.CandidateDisposition
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.TransitionId

internal sealed interface ChartSelection {
    data object Overview : ChartSelection

    data class Node(
        val id: StateId,
    ) : ChartSelection

    data class Transition(
        val id: TransitionId,
    ) : ChartSelection
}

internal enum class ChartActivity { Active, Previous, Inactive }

internal data class ChartNodePresentation(
    val id: StateId,
    val position: Pair<Int, Int>,
    val activity: ChartActivity,
)

internal data class ChartEdgePresentation(
    val from: Pair<Int, Int>,
    val to: Pair<Int, Int>,
    val highlighted: Boolean,
)

internal data class ChartDetailPresentation(
    val heading: String,
    val lines: List<String>,
)

internal fun chartPositions(chart: StateChartDefinition): Map<StateId, Pair<Int, Int>> =
    chart.states
        .distinctBy { it.id }
        .mapIndexed { index, node -> node.id to (index % 3 to index / 3) }
        .toMap()

internal fun chartNodes(
    positions: Map<StateId, Pair<Int, Int>>,
    snapshot: MachineSnapshot<*>,
    previousActive: Set<StateId>,
): List<ChartNodePresentation> =
    positions.map { (id, position) ->
        ChartNodePresentation(
            id,
            position,
            when {
                snapshot.isActive(id) -> ChartActivity.Active
                id in previousActive -> ChartActivity.Previous
                else -> ChartActivity.Inactive
            },
        )
    }

internal fun chartEdges(
    chart: StateChartDefinition,
    positions: Map<StateId, Pair<Int, Int>>,
    selection: ChartSelection,
    inspection: ChartInspection,
): List<ChartEdgePresentation> =
    chart.transitions
        .withIndex()
        .filter { it.value.source in positions && it.value.target in positions }
        .map { (index, transition) ->
            val id = TransitionId(index)
            var highlighted = selection == ChartSelection.Transition(id)
            inspection.withExplanation { explanation -> if (explanation.candidates.any { it.transition == id && it.disposition == CandidateDisposition.Selected }) highlighted = true }
            ChartEdgePresentation(positions.getValue(transition.source), positions.getValue(transition.target), highlighted)
        }

internal fun chartNodeDetail(
    chart: StateChartDefinition,
    snapshot: MachineSnapshot<*>,
    id: StateId,
): ChartDetailPresentation =
    ChartDetailPresentation(
        "State $id · parent ${chart.node(id).parent} · activation ${snapshot.activations[id] ?: "none"}",
        snapshot.timers.filter { (_, record) -> chart.transitions.getOrNull(record.transition.index)?.source == id }.map { (timer, record) -> "Timer $timer · ${record.deadline}" },
    )

internal fun chartTransitionDetail(
    chart: StateChartDefinition,
    id: TransitionId,
    inspection: ChartInspection,
): ChartDetailPresentation {
    val transition = chart.transitions[id.index]
    var disposition = "no evaluation recorded"
    inspection.withExplanation { explanation ->
        for (candidate in explanation.candidates) {
            if (candidate.transition == id) {
                disposition = candidate.disposition.toString()
                break
            }
        }
    }
    val guards =
        if (inspection is ChartInspection.Decided) {
            inspection.explanation.guards
                .filter { it.transition == id }
                .map { "${it.label} = ${it.result}" }
        } else {
            emptyList()
        }
    return ChartDetailPresentation("$id · ${transition.kind} · $disposition", guards)
}
