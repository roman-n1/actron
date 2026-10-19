package actron.timetravel.compose

import actron.statechart.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.*
import kotlin.math.pow

/** Explicit graph data for the selected immutable history entry; no parsing of diagnostic text. */
sealed interface HistoryGraph {
    data object Unavailable : HistoryGraph

    data class Machine(
        val definition: StateChartDefinition,
        val active: Set<StateId>,
    ) : HistoryGraph
}

internal data class GraphNode(
    val state: StateNode,
    val bounds: Rect,
)

internal data class GraphEdge(
    val transition: Transition,
    val points: List<Offset>,
    val label: Offset,
)

internal data class GraphLayout(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val size: Size,
)

/** Declaration order is stable across snapshots. Containers reserve routing space beside children. */
internal fun layoutGraph(chart: StateChartDefinition): GraphLayout {
    val nodes = mutableListOf<GraphNode>()
    val visited = mutableSetOf<StateId>()
    val transitions = chart.transitions.filter { chart.hasNode(it.source) && chart.hasNode(it.target) }

    fun owner(edge: Transition): StateParent =
        chart
            .ancestorsOf(edge.source)
            .filter { it in chart.ancestorsOf(edge.target) }
            .take(1)
            .fold<StateId, StateParent>(StateParent.Root) { _, id -> id }
    val owners = transitions.map(::owner)

    fun gutter(parent: StateParent) = 48f + owners.count { it == parent } * 28f

    fun ports(id: StateId) = transitions.sumOf { (if (it.source == id) 1 else 0) + (if (it.target == id) 1 else 0) }

    fun place(
        state: StateNode,
        x: Float,
        y: Float,
    ): Size {
        if (!visited.add(state.id)) return Size.Zero
        val children = chart.childrenOf(state.id).filter { it.id !in visited }
        val header = maxOf(76f, 32f + ports(state.id) * 20f)
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
        val size = Size(width + 48f + gutter(state.id), height + header + 24f)
        nodes.add(start, GraphNode(state, Rect(Offset(x, y), size)))
        return size
    }
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
    val byId = nodes.associateBy { it.state.id }
    val usedPorts = mutableMapOf<StateId, Int>()
    val usedLanes = mutableMapOf<StateParent, Int>()

    fun port(id: StateId): Offset {
        val index = usedPorts.getOrElse(id) { 0 }
        usedPorts[id] = index + 1
        val rect = byId.getValue(id).bounds
        return Offset(rect.right, rect.top + 24f + index * 20f)
    }
    val edges =
        transitions.mapIndexed { index, transition ->
            val parent = owners[index]
            val laneIndex = usedLanes.getOrElse(parent) { 0 }
            usedLanes[parent] = laneIndex + 1
            val right = if (parent is StateId) byId.getValue(parent).bounds.right - gutter(parent) else width
            val lane = right + 20f + laneIndex * 28f
            val from = port(transition.source)
            val to = port(transition.target)
            GraphEdge(transition, listOf(from, Offset(lane, from.y), Offset(lane, to.y), to), Offset(lane + 4f, maxOf(from.y, to.y) - 8f))
        }
    return GraphLayout(nodes, edges, Size(width + gutter(StateParent.Root), maxOf(200f, y)))
}

internal class GraphViewport {
    var initialized = false
        private set
    var scale by mutableStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    fun transform(
        anchor: Offset,
        pan: Offset,
        zoom: Float,
    ) {
        if (!zoom.isFinite() || zoom <= 0f) return
        val next = (scale * zoom).coerceIn(0.1f, 4f)
        offset = anchor - (anchor - offset) * (next / scale) + pan
        scale = next
    }

    fun fit(
        view: Size,
        graph: Size,
    ) {
        if (view.width <= 0f || view.height <= 0f) return
        initialized = true
        scale = minOf(view.width / graph.width, view.height / graph.height).coerceIn(0.1f, 1.5f)
        offset = Offset((view.width - graph.width * scale) / 2f, (view.height - graph.height * scale) / 2f)
    }
}

@Composable
internal fun MachineGraph(
    graph: HistoryGraph,
    viewport: GraphViewport,
    modifier: Modifier = Modifier,
) {
    when (graph) {
        HistoryGraph.Unavailable -> Box(modifier.padding(16.dp)) { Text(historyText(HistoryText.NoGraph)) }
        is HistoryGraph.Machine -> GraphCanvas(graph, viewport, modifier)
    }
}

@Composable
private fun GraphCanvas(
    graph: HistoryGraph.Machine,
    viewport: GraphViewport,
    modifier: Modifier,
) {
    val layout = remember(graph.definition) { layoutGraph(graph.definition) }
    var view by remember { mutableStateOf(Size.Zero) }
    var selected by remember(graph.definition) { mutableStateOf(emptyList<StateId>()) }
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, color = colors.onSurface)
    val zoomIn = historyText(HistoryText.ZoomIn)
    val zoomOut = historyText(HistoryText.ZoomOut)
    val activeLabel = historyText(HistoryText.ActiveStates)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton({ viewport.transform(view.center, Offset.Zero, 1f / 1.25f) }, Modifier.testTag("graph-zoom-out").semantics { contentDescription = zoomOut }) { Text("−") }
            Text("${(viewport.scale * 100).toInt()}%", Modifier.padding(top = 12.dp).testTag("graph-scale"))
            TextButton({ viewport.transform(view.center, Offset.Zero, 1.25f) }, Modifier.testTag("graph-zoom-in").semantics { contentDescription = zoomIn }) { Text("+") }
            TextButton({ viewport.fit(view, layout.size) }, Modifier.testTag("graph-fit")) { Text(historyText(HistoryText.Fit)) }
        }
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("machine-graph")
                .semantics { contentDescription = "$activeLabel: ${graph.active.joinToString { it.value }}" }
                .onSizeChanged {
                    val first = view == Size.Zero
                    view = Size(it.width.toFloat(), it.height.toFloat())
                    if (first && !viewport.initialized) viewport.fit(view, layout.size)
                }.pointerInput(viewport, layout) {
                    detectTapGestures { point ->
                        val location = (point - viewport.offset) / viewport.scale
                        selected =
                            layout.nodes
                                .asReversed()
                                .filter { it.bounds.contains(location) }
                                .take(1)
                                .map { it.state.id }
                    }
                }.pointerInput(viewport) {
                    detectTransformGestures { centroid, pan, zoom, _ -> viewport.transform(centroid, pan, zoom) }
                }.pointerInput(viewport) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Scroll) {
                                event.changes.forEach { change ->
                                    if (event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed) {
                                        viewport.transform(change.position, Offset.Zero, 1.12f.pow(-change.scrollDelta.y))
                                    } else {
                                        viewport.transform(change.position, change.scrollDelta * -32f, 1f)
                                    }
                                    change.consume()
                                }
                            }
                        }
                    }
                },
        ) {
            clipRect {
                withTransform({
                    translate(viewport.offset.x, viewport.offset.y)
                    scale(viewport.scale, viewport.scale, Offset.Zero)
                }) {
                    layout.nodes.forEach { node ->
                        val active = node.state.id in graph.active
                        val rect = node.bounds
                        drawRoundRect(if (active) colors.primaryContainer else colors.surfaceContainerLow, rect.topLeft, rect.size, CornerRadius(12f))
                        drawRoundRect(if (node.state.id in selected || active) colors.primary else colors.outlineVariant, rect.topLeft, rect.size, CornerRadius(12f), style = Stroke(if (active) 3f else 1f))
                        val kind =
                            when (node.state) {
                                is ParallelState -> ""
                                is FinalState -> " ◉"
                                is HistoryState -> if (node.state.deep) " H*" else " H"
                                else -> ""
                            }
                        val text =
                            measurer.measure(
                                node.state.id.value
                                    .substringAfterLast('.') + kind,
                                style,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                constraints = Constraints(maxWidth = (rect.width - 24f).toInt()),
                            )
                        drawText(text, topLeft = rect.topLeft + Offset(12f, 18f))
                        val initial = node.state.id == graph.definition.initial || graph.definition.states.any { it is CompoundState && it.initial == node.state.id }
                        if (initial) drawCircle(colors.primary, 4f, rect.topLeft + Offset(5f, 34f))
                    }
                    layout.edges.forEach { edge ->
                        val path =
                            Path().apply {
                                moveTo(edge.points.first().x, edge.points.first().y)
                                edge.points.drop(1).forEach { lineTo(it.x, it.y) }
                            }
                        val edgeColor = if (edge.transition.source in selected) colors.primary else colors.outline
                        drawPath(path, colors.surface, style = Stroke(5f))
                        drawPath(path, edgeColor, style = Stroke(if (edge.transition.source in selected) 2.5f else 1.3f))
                        val end = edge.points.last()
                        drawLine(edgeColor, end, end + Offset(8f, -5f), 1.5f)
                        drawLine(edgeColor, end, end + Offset(8f, 5f), 1.5f)
                    }
                    // Labels are painted last so crossing routes never strike through their text.
                    layout.edges.forEach { edge ->
                        val available = kotlin.math.abs(edge.points.first().y - edge.points.last().y) - 16f
                        val text = measurer.measure(edge.transition.triggerLabel, style.copy(fontSize = 11.sp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, constraints = Constraints(maxWidth = maxOf(1, available.toInt())))
                        if (available > 40f) {
                            rotate(-90f, edge.label) {
                                drawRect(colors.surface, edge.label - Offset(2f, 1f), Size(text.size.width + 4f, text.size.height + 2f))
                                drawText(text, topLeft = edge.label)
                            }
                        }
                    }
                }
            }
        }
        selected.forEach { id ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 140.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
                    .testTag("graph-state-transitions"),
            ) {
                Text(id.value, style = MaterialTheme.typography.titleSmall)
                graph.definition.transitionsFrom(id).forEach { transition ->
                    Text("${transition.triggerLabel}: ${transition.target.value}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text(historyText(HistoryText.GraphHint), Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}
