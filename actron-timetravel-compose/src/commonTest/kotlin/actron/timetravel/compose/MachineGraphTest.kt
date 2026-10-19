package actron.timetravel.compose

import actron.statechart.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.*

class MachineGraphTest {
    @Test
    fun nestedParallelRegionsKeepTheirEdgesInsideAndPortsDistinct() {
        val root = StateId("Parallel")
        val left = StateId("Current")
        val right = StateId("Hourly")
        val a = StateId("Current.Idle")
        val b = StateId("Current.Loading")
        val c = StateId("Hourly.Idle")
        val d = StateId("Hourly.Loading")
        val chart =
            StateChartDefinition(
                root,
                listOf(ParallelState(root), CompoundState(left, a, root), AtomicState(a, left), AtomicState(b, left), CompoundState(right, c, root), AtomicState(c, right), AtomicState(d, right)),
                listOf(
                    Transition(a, b, ActionMatcher("Load")),
                    Transition(a, b, ActionMatcher("Retry")),
                    Transition(b, a, ActionMatcher("Done")),
                    Transition(b, b, ActionMatcher("Refresh")),
                    Transition(c, d, ActionMatcher("Load")),
                    Transition(d, c, ActionMatcher("Done")),
                ),
            )
        val layout = layoutGraph(chart)
        assertEquals(layout, layoutGraph(chart))
        assertEquals(chart.states.size, layout.nodes.size)
        assertEquals(chart.transitions.size, layout.edges.size)
        val nodes = layout.nodes.associateBy { it.state.id }
        assertFalse(nodes.getValue(left).bounds.overlaps(nodes.getValue(right).bounds))
        layout.nodes.filter { it.state.parent is StateId }.forEach { node ->
            val parent = nodes.getValue(node.state.parent as StateId).bounds
            assertTrue(parent.contains(node.bounds.topLeft))
            assertTrue(parent.contains(node.bounds.bottomRight - Offset(1f, 1f)))
        }
        val ports = layout.edges.flatMap { listOf(it.points.first(), it.points.last()) }
        assertEquals(ports.size, ports.distinct().size)
        layout.edges.forEach { edge ->
            val region =
                if (edge.transition.source.value
                        .startsWith("Current")
                ) {
                    left
                } else {
                    right
                }
            edge.points.forEach { assertTrue(nodes.getValue(region).bounds.contains(it)) }
        }
        // Crossings may exist in a directed graph; shared collinear segments must not.
        val segments = layout.edges.flatMap { it.points.zipWithNext() }
        segments.forEachIndexed { index, (a1, a2) ->
            segments.drop(index + 1).forEach { (b1, b2) ->
                if (a1.x == a2.x && b1.x == b2.x && a1.x == b1.x) {
                    assertFalse(maxOf(minOf(a1.y, a2.y), minOf(b1.y, b2.y)) < minOf(maxOf(a1.y, a2.y), maxOf(b1.y, b2.y)))
                }
                if (a1.y == a2.y && b1.y == b2.y && a1.y == b1.y) {
                    assertFalse(maxOf(minOf(a1.x, a2.x), minOf(b1.x, b2.x)) < minOf(maxOf(a1.x, a2.x), maxOf(b1.x, b2.x)))
                }
            }
        }
    }

    @Test
    fun zoomKeepsPointerAnchorAndClampsWhileFitRecenters() {
        val viewport = GraphViewport()
        viewport.fit(Size(800f, 600f), Size(1000f, 800f))
        val anchor = Offset(120f, 90f)
        val point = (anchor - viewport.offset) / viewport.scale
        viewport.transform(anchor, Offset.Zero, 2f)
        assertEquals(anchor, point * viewport.scale + viewport.offset)
        viewport.transform(anchor, Offset(30f, -15f), 1f)
        assertEquals(anchor + Offset(30f, -15f), point * viewport.scale + viewport.offset)
        viewport.transform(anchor, Offset.Zero, 10000f)
        assertEquals(4f, viewport.scale)
        viewport.transform(anchor, Offset.Zero, 0.00001f)
        assertEquals(0.1f, viewport.scale)
        viewport.transform(anchor, Offset.Zero, Float.NaN)
        assertEquals(0.1f, viewport.scale)
        viewport.fit(Size(800f, 600f), Size(1000f, 800f))
        assertEquals(Offset(25f, 0f), viewport.offset)
    }

    @Test
    fun malformedHierarchyDoesNotLoopOrLoseNodes() {
        val a = StateId("A")
        val b = StateId("B")
        val layout = layoutGraph(StateChartDefinition(a, listOf(CompoundState(a, b, b), CompoundState(b, a, a)), emptyList()))
        assertEquals(setOf(a, b), layout.nodes.map { it.state.id }.toSet())
        assertTrue(layout.size.width.isFinite())
    }
}
