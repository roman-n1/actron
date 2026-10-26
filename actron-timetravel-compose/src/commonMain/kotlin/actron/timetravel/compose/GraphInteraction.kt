package actron.timetravel.compose

import actron.statechart.GraphDiagnostic
import actron.statechart.StateId
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import kotlin.math.pow

/** Selection lives as long as the current definition. */
internal class GraphInteraction {
    var selected by mutableStateOf(emptyList<StateId>())
        private set
    var selectedEdges by mutableStateOf(emptyList<Int>())
        private set

    fun select(
        point: Offset,
        layout: GraphLayout,
        viewport: GraphViewport,
    ) {
        val location = (point - viewport.offset) / viewport.scale
        selectedEdges =
            layout.edges.indices
                .filter { layout.edges[it].label.contains(location) }
                .take(1)
        selected =
            if (selectedEdges.isNotEmpty()) {
                emptyList()
            } else {
                layout.nodes
                    .asReversed()
                    .filter { it.bounds.contains(location) }
                    .take(1)
                    .map { it.state.id }
            }
    }

    fun focus(
        issue: GraphDiagnostic,
        layout: GraphLayout,
        surface: GraphSurface,
        viewport: GraphViewport,
    ) {
        selectedEdges = layout.edges.indices.filter { layout.edges[it].id in issue.transitions }
        selected = if (selectedEdges.isEmpty()) issue.states.toList() else emptyList()
        val boxes =
            if (selectedEdges.isNotEmpty()) {
                selectedEdges.map { layout.edges[it].label }
            } else {
                layout.nodes.filter { it.state.id in selected }.map { it.bounds }
            }
        if (boxes.isNotEmpty()) {
            val anchor = boxes.first().center * viewport.scale + viewport.offset
            viewport.transform(anchor, surface.size.center - anchor, maxOf(viewport.scale, 0.8f) / viewport.scale)
        }
    }
}

/** Canvas size survives definition changes, just as the host-owned viewport does. */
internal class GraphSurface {
    var size by mutableStateOf(Size.Zero)
        private set

    fun resize(
        next: Size,
        viewport: GraphViewport,
        graph: Size,
    ) {
        val first = size == Size.Zero
        size = next
        if (first && !viewport.initialized) viewport.fit(size, graph)
    }
}

internal fun GraphViewport.scroll(
    anchor: Offset,
    delta: Offset,
    modifiers: PointerKeyboardModifiers,
) {
    if (modifiers.isCtrlPressed || modifiers.isMetaPressed) {
        transform(anchor, Offset.Zero, 1.12f.pow(-delta.y))
    } else {
        transform(anchor, delta * -32f, 1f)
    }
}
