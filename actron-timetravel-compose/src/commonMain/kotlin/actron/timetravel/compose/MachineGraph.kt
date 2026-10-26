package actron.timetravel.compose

import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.machine.MachineProfileSnapshot
import actron.statechart.machine.TransitionId
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** Coverage is optional. Counts refer to the supplied definition's transition indices. */
sealed interface HistoryGraphCoverage {
    data object Unavailable : HistoryGraphCoverage

    data class Recorded(
        val hits: Map<TransitionId, Long>,
    ) : HistoryGraphCoverage {
        init {
            require(hits.values.all { it > 0 })
        }
    }
}

/** Explicit graph data for the selected immutable history entry; no parsing of diagnostic text. */
sealed interface HistoryGraph {
    data object Unavailable : HistoryGraph

    data class Machine(
        val definition: StateChartDefinition,
        val active: Set<StateId>,
        val coverage: HistoryGraphCoverage = HistoryGraphCoverage.Unavailable,
        val profile: HistoryGraphProfile = HistoryGraphProfile.Unavailable,
    ) : HistoryGraph
}

/** Compatibility payload; the inspector no longer displays execution timings. */
sealed interface HistoryGraphProfile {
    data object Unavailable : HistoryGraphProfile

    data class Recorded(
        val snapshot: MachineProfileSnapshot,
    ) : HistoryGraphProfile
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
    val surface = remember { GraphSurface() }
    val selection = remember(graph.definition) { GraphInteraction() }
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodySmall.copy(color = colors.onSurface)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurface)
    val labels =
        remember(graph.definition, measurer, labelStyle) {
            graph.definition.transitions.associateWith { measurer.measure(it.triggerLabel, labelStyle, constraints = Constraints(maxWidth = 320)) }
        }
    val layout =
        remember(graph.definition, labels) {
            layoutGraph(graph.definition) { transition -> labels.getValue(transition).size.let { Size(it.width.toFloat(), it.height.toFloat()) } }
        }
    val measurements = graphMeasurements(graph)
    val appearance = graphAppearance(graph, layout, selection, measurements, colors)
    val nativeZoom = nativeGraphZoom { anchor, zoom -> viewport.transform(anchor, Offset.Zero, zoom) }
    val activeLabel = historyText(HistoryText.ActiveStates)
    Column(modifier) {
        GraphDiagnosticsPanel(graph.definition.diagnostics) { selection.focus(it, layout, surface, viewport) }
        GraphControls(graph, viewport, surface, layout, measurements)
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("machine-graph")
                .then(nativeZoom)
                .semantics { contentDescription = "$activeLabel: ${graph.active.joinToString { it.value }}" }
                .graphGestures(layout, viewport, selection, surface),
        ) {
            drawGraph(appearance, viewport, colors, measurer, style, labels)
        }
        GraphDetails(graph, layout, selection, measurements)
    }
}
