package actron.timetravel.compose

import actron.statechart.StateId
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun StateChartDiagram(
    nodes: List<ChartNodePresentation>,
    edges: List<ChartEdgePresentation>,
    highlight: androidx.compose.ui.graphics.Color,
    outline: androidx.compose.ui.graphics.Color,
    onSelect: (StateId) -> Unit,
) {
    Box(Modifier.horizontalScroll(rememberScrollState())) {
        Box(Modifier.width(580.dp).height((maxOf(1, (nodes.size + 2) / 3) * 96).dp)) {
            Canvas(Modifier.matchParentSize()) { drawStateChartEdges(edges, highlight, outline) }
            for (node in nodes) {
                val (x, y) = node.position
                val activity = node.activity
                Surface(
                    color =
                        when {
                            activity == ChartActivity.Active -> MaterialTheme.colorScheme.primaryContainer
                            activity == ChartActivity.Previous -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                    shape = MaterialTheme.shapes.small,
                    modifier =
                        Modifier
                            .offset((x * 190 + 18).dp, (y * 96 + 18).dp)
                            .width(158.dp)
                            .height(60.dp)
                            .clickable { onSelect(node.id) }
                            .testTag("chart-node-${node.id.value}"),
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text(node.id.value, maxLines = 1)
                        Text(
                            if (activity == ChartActivity.Active) {
                                "active"
                            } else if (activity == ChartActivity.Previous) {
                                "previous"
                            } else {
                                "inactive"
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}
