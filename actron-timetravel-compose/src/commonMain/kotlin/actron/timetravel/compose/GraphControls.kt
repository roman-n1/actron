package actron.timetravel.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun GraphControls(
    graph: HistoryGraph.Machine,
    viewport: GraphViewport,
    surface: GraphSurface,
    layout: GraphLayout,
    measurements: GraphMeasurements,
    showProfileWindow: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val zoomIn = historyText(HistoryText.ZoomIn)
    val zoomOut = historyText(HistoryText.ZoomOut)
    if (graph.coverage is HistoryGraphCoverage.Recorded) {
        Text("${historyText(HistoryText.Coverage)}: ${measurements.covered} / ${graph.definition.transitions.size} · ${historyText(HistoryText.CoverageLegend)}", Modifier.padding(horizontal = 8.dp).testTag("graph-coverage"), style = MaterialTheme.typography.labelSmall)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
        if (measurements.profiles.isNotEmpty()) {
            Text(historyText(HistoryText.Profile), Modifier.weight(1f).padding(top = 12.dp).testTag("graph-timings-title"), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
        TextButton({ viewport.transform(surface.size.center, Offset.Zero, 1f / 1.25f) }, Modifier.testTag("graph-zoom-out").semantics { contentDescription = zoomOut }) { Text("−") }
        Text("${(viewport.scale * 100).toInt()}%", Modifier.padding(top = 12.dp).testTag("graph-scale"))
        TextButton({ viewport.transform(surface.size.center, Offset.Zero, 1.25f) }, Modifier.testTag("graph-zoom-in").semantics { contentDescription = zoomIn }) { Text("+") }
        TextButton({ viewport.fit(surface.size, layout.size) }, Modifier.testTag("graph-fit")) { Text(historyText(HistoryText.Fit)) }
    }
    if (showProfileWindow) {
        Text(historyText(HistoryText.ProfileWindow), Modifier.padding(horizontal = 8.dp).testTag("graph-profile-window"), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        for (profile in measurements.profiles) if (profile.droppedCorrelations > 0) Text("${historyText(HistoryText.ProfileDropped)}: ${profile.droppedCorrelations}", color = colors.error, style = MaterialTheme.typography.labelSmall)
    }
}
