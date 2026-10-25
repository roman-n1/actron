package actron.timetravel.compose

import actron.statechart.ParallelState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.StateNode
import actron.statechart.StateParent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

internal data class GraphPlacement(
    val nodes: List<GraphNode>,
    val width: Float,
    val size: Size,
)

/** Traversal state is local to one layout; orphan/cyclic definitions remain inspectable. */
internal class GraphNodePlacement(
    private val chart: StateChartDefinition,
    private val routing: GraphRoutingMetrics,
) {
    private val nodes = mutableListOf<GraphNode>()
    private val visited = mutableSetOf<StateId>()

    fun place(): GraphPlacement {
        var y = 40f
        var width = 0f
        chart.states.filter { it.parent == StateParent.Root }.forEach { state ->
            val size = place(state, 40f, y)
            y += size.height + 48f
            width = maxOf(width, size.width + 80f)
        }
        // Malformed or orphan definitions remain inspectable without recursive cycles.
        chart.states.filter { it.id !in visited }.forEach { state ->
            val size = place(state, 40f, y)
            y += size.height + 48f
            width = maxOf(width, size.width + 80f)
        }
        return GraphPlacement(nodes.toList(), width, Size(width + routing.gutter(StateParent.Root), maxOf(200f, y)))
    }

    private fun place(
        state: StateNode,
        x: Float,
        y: Float,
    ): Size {
        if (!visited.add(state.id)) return Size.Zero
        val children = chart.childrenOf(state.id).filter { it.id !in visited }
        val header = maxOf(76f, 32f + routing.portHeight(state.id))
        if (children.isEmpty()) {
            val size = Size(240f, header)
            nodes.add(GraphNode(state, Rect(Offset(x, y), size)))
            return size
        }
        val start = nodes.size
        var dx = x + 24f
        var dy = y + header
        var width = 0f
        var height = 0f
        children.forEach { child ->
            val size = place(child, dx, dy)
            if (state is ParallelState) {
                dx += size.width + 32f
                width += size.width + 32f
                height = maxOf(height, size.height)
            } else {
                dy += size.height + 48f
                height += size.height + 48f
                width = maxOf(width, size.width)
            }
        }
        val size = Size(width + 48f + routing.gutter(state.id), height + header + 24f)
        nodes.add(start, GraphNode(state, Rect(Offset(x, y), size)))
        return size
    }
}
