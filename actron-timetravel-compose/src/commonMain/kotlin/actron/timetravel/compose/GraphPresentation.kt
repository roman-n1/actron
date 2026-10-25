package actron.timetravel.compose

import actron.statechart.CompoundState
import actron.statechart.FinalState
import actron.statechart.HistoryState
import actron.statechart.ParallelState
import actron.statechart.StateId
import actron.statechart.machine.MachineProfileSnapshot
import actron.statechart.machine.TransitionId
import actron.statechart.machine.TransitionProfile
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

internal data class GraphMeasurements(
    val profiles: List<MachineProfileSnapshot>,
    val transitions: Map<TransitionId, TransitionProfile>,
    val maximumProcessing: Long,
    val counts: Map<TransitionId, Long>,
    val covered: Int,
)

internal fun graphMeasurements(graph: HistoryGraph.Machine): GraphMeasurements {
    val profiles =
        when (val profile = graph.profile) {
            HistoryGraphProfile.Unavailable -> emptyList()
            is HistoryGraphProfile.Recorded -> listOf(profile.snapshot)
        }
    val transitions = profiles.flatMap { it.transitions.entries }.associate { it.toPair() }
    val counts =
        when (val coverage = graph.coverage) {
            HistoryGraphCoverage.Unavailable -> emptyMap()
            is HistoryGraphCoverage.Recorded -> coverage.hits
        }
    return GraphMeasurements(
        profiles,
        transitions,
        transitions.values.fold(0L) { maximum, value -> maxOf(maximum, value.processing.average.inWholeNanoseconds) },
        counts,
        graph.definition.transitions.indices
            .count { counts.getOrElse(TransitionId(it)) { 0 } > 0 },
    )
}

internal data class GraphNodeAppearance(
    val node: GraphNode,
    val fill: Color,
    val outline: Color,
    val stroke: Float,
    val title: String,
    val initial: Boolean,
)

internal data class GraphStroke(
    val width: Float,
    val dashed: Boolean,
)

internal data class GraphEdgeAppearance(
    val edge: GraphEdge,
    val color: Color,
    val stroke: GraphStroke,
    val labelFill: Color,
    val labelOutline: Color,
    val labelStroke: Float,
)

internal data class GraphAppearance(
    val nodes: List<GraphNodeAppearance>,
    val edges: List<GraphEdgeAppearance>,
    val showProfileWindow: Boolean,
)

internal fun graphAppearance(
    graph: HistoryGraph.Machine,
    layout: GraphLayout,
    selection: GraphInteraction,
    measurements: GraphMeasurements,
    colors: ColorScheme,
): GraphAppearance {
    val endpoints = selection.selectedEdges.flatMap { index -> layout.edges[index].transition.let { listOf(it.source, it.target) } }
    val initial =
        graph.definition.states
            .filterIsInstance<CompoundState>()
            .map { it.initial } + graph.definition.initial
    return GraphAppearance(
        layout.nodes.map { nodeAppearance(it, graph, selection, endpoints, initial, colors) },
        layout.edges.mapIndexed { index, edge -> edgeAppearance(edge, index, graph, selection, measurements, colors) },
        selection.profiling.value && measurements.profiles.isNotEmpty(),
    )
}

private fun nodeAppearance(
    node: GraphNode,
    graph: HistoryGraph.Machine,
    selection: GraphInteraction,
    endpoints: List<StateId>,
    initial: List<StateId>,
    colors: ColorScheme,
): GraphNodeAppearance {
    val active = node.state.id in graph.active
    val highlighted = active || node.state.id in selection.selected || node.state.id in endpoints
    val kind =
        when (val state = node.state) {
            is ParallelState -> ""
            is FinalState -> " ◉"
            is HistoryState -> if (state.deep) " H*" else " H"
            else -> ""
        }
    return GraphNodeAppearance(
        node,
        if (active) colors.primaryContainer else colors.surfaceContainerLow,
        if (highlighted) colors.primary else colors.outlineVariant,
        if (active || node.state.id in endpoints) 3f else 1f,
        node.state.id.value
            .substringAfterLast('.') + kind,
        node.state.id in initial,
    )
}

private fun edgeAppearance(
    edge: GraphEdge,
    index: Int,
    graph: HistoryGraph.Machine,
    selection: GraphInteraction,
    measurements: GraphMeasurements,
    colors: ColorScheme,
): GraphEdgeAppearance {
    val focused = index in selection.selectedEdges
    val highlighted = focused || edge.transition.source in selection.selected
    val covered = measurements.counts.getOrElse(edge.id) { 0 } > 0
    val timing = measurements.transitions.getOrElse(edge.id) { TransitionProfile() }
    val heat =
        if (selection.profiling.value && measurements.maximumProcessing > 0) {
            timing.processing.average.inWholeNanoseconds
                .toFloat() / measurements.maximumProcessing
        } else {
            0f
        }
    return GraphEdgeAppearance(
        edge,
        edgeColor(highlighted, heat, covered, selection.selectedEdges.isNotEmpty(), colors),
        edgeStroke(graph, covered, highlighted),
        if (focused) colors.secondaryContainer else colors.surface,
        if (focused) colors.primary else colors.outlineVariant,
        if (focused) 2f else 1f,
    )
}

private fun edgeStroke(
    graph: HistoryGraph.Machine,
    covered: Boolean,
    highlighted: Boolean,
): GraphStroke =
    if (graph.coverage is HistoryGraphCoverage.Recorded && !covered && !highlighted) {
        GraphStroke(1.3f, dashed = true)
    } else {
        GraphStroke(
            when {
                highlighted -> 3f
                covered -> 2.5f
                else -> 1.3f
            },
            dashed = false,
        )
    }

private fun edgeColor(
    highlighted: Boolean,
    heat: Float,
    covered: Boolean,
    dimmed: Boolean,
    colors: ColorScheme,
): Color =
    if (highlighted) {
        colors.primary
    } else {
        val base =
            when {
                heat > 0f -> lerp(colors.outline, colors.error, heat)
                covered -> colors.tertiary
                else -> colors.outline
            }
        base.copy(alpha = if (!dimmed) 1f else 0.25f)
    }
