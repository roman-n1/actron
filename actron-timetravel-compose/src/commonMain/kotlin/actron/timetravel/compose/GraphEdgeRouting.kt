package actron.timetravel.compose

import actron.statechart.StateId
import actron.statechart.StateParent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

internal class GraphEdgeRouting(
    private val placement: GraphPlacement,
    private val routing: GraphRoutingMetrics,
) {
    private val byId = placement.nodes.associateBy { it.state.id }
    private val usedPorts = mutableMapOf<StateId, Float>()
    private val usedLanes = mutableMapOf<StateParent, Int>()

    fun route(): List<GraphEdge> =
        routing.routes.map { route ->
            val transition = route.transition
            val parent = route.owner
            val laneIndex = usedLanes.getOrElse(parent) { 0 }
            usedLanes[parent] = laneIndex + 1
            val right = if (parent is StateId) byId.getValue(parent).bounds.right - routing.gutter(parent) else placement.width
            val lane = right + routing.labelSpace(parent) + 20f + laneIndex * 28f
            val from = port(transition.source, route.labelSize.height + 12f)
            val to = port(transition.target, 20f)
            val label = Rect(Offset(right + 12f + route.column * (routing.labelWidth(parent) + 24f), from.y - route.labelSize.height / 2f), route.labelSize)
            GraphEdge(transition, route.id, listOf(from, Offset(lane, from.y), Offset(lane, to.y), to), label)
        }

    private fun port(
        id: StateId,
        height: Float,
    ): Offset {
        val previous = usedPorts.getOrElse(id) { 0f }
        usedPorts[id] = previous + height
        val rect = byId.getValue(id).bounds
        return Offset(rect.right, rect.top + 16f + previous + height / 2f)
    }
}
