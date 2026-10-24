package actron.timetravel.compose

import actron.statechart.StateChartDefinition
import actron.statechart.StateNode
import actron.statechart.Transition
import actron.statechart.machine.TransitionId
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

internal data class GraphNode(
    val state: StateNode,
    val bounds: Rect,
)

internal data class GraphEdge(
    val transition: Transition,
    val id: TransitionId,
    val points: List<Offset>,
    val label: Rect,
)

internal data class GraphLayout(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val size: Size,
)

/** Declaration order and original transition IDs survive layout, including malformed definitions. */
internal fun layoutGraph(
    chart: StateChartDefinition,
    measureLabel: (Transition) -> Size = { Size(it.triggerLabel.length * 8f, 20f) },
): GraphLayout {
    val routing = GraphRoutingMetrics(chart, measureLabel)
    val placement = GraphNodePlacement(chart, routing).place()
    return GraphLayout(placement.nodes, GraphEdgeRouting(placement, routing).route(), placement.size)
}
