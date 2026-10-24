package actron.timetravel.compose

import actron.statechart.ParallelState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.StateParent
import actron.statechart.Transition
import actron.statechart.machine.TransitionId
import androidx.compose.ui.geometry.Size

internal data class GraphRoute(
    val transition: Transition,
    val id: TransitionId,
    val owner: StateParent,
    val labelSize: Size,
    val column: Int,
)

/** Measures valid edges once and owns the space shared by placement and edge routing. */
internal class GraphRoutingMetrics(
    private val chart: StateChartDefinition,
    measureLabel: (Transition) -> Size,
) {
    val routes =
        chart.transitions
            .withIndex()
            .filter { chart.hasNode(it.value.source) && chart.hasNode(it.value.target) }
            .map { (index, edge) ->
                val parent = owner(edge)
                val measured = measureLabel(edge)
                GraphRoute(edge, TransitionId(index), parent, Size(measured.width + 24f, measured.height + 16f), labelColumn(edge, parent))
            }

    private fun owner(edge: Transition): StateParent =
        chart
            .ancestorsOf(edge.source)
            .filter { it in chart.ancestorsOf(edge.target) }
            .take(1)
            .fold<StateId, StateParent>(StateParent.Root) { _, id -> id }

    private fun labelColumn(
        edge: Transition,
        parent: StateParent,
    ): Int {
        if (parent !is StateId || !chart.nodeSatisfies(parent) { it is ParallelState }) return 0
        return chart.childrenOf(parent).indexOfFirst { it.id == edge.source || it.id in chart.ancestorsOf(edge.source) }.coerceAtLeast(0)
    }

    fun labelWidth(parent: StateParent) = routes.filter { it.owner == parent }.fold(0f) { width, route -> maxOf(width, route.labelSize.width) }

    fun labelSpace(parent: StateParent): Float {
        val columns = routes.filter { it.owner == parent }.fold(1) { count, route -> maxOf(count, route.column + 1) }
        return columns * (labelWidth(parent) + 24f)
    }

    fun gutter(parent: StateParent) = 48f + labelSpace(parent) + routes.count { it.owner == parent } * 28f

    fun portHeight(id: StateId): Float =
        routes.fold(0f) { height, route ->
            height + (if (route.transition.source == id) route.labelSize.height + 12f else 0f) + (if (route.transition.target == id) 20f else 0f)
        }
}
