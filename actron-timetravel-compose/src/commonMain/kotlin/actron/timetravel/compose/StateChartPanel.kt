package actron.timetravel.compose

import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.TransitionId
import actron.timetravel.compose.resources.Res
import actron.timetravel.compose.resources.invariant_failed
import actron.timetravel.compose.resources.snapshot_counts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource

/**
 * Interactive diagram over read-only model/snapshot data. Select a node or transition to inspect
 * activity, timers, candidate disposition and actual guard evaluations. No guard or decision runs
 * during composition, and no context/action payload is rendered implicitly.
 */
@Composable
fun <C : Any> StateChartPanel(
    chart: StateChartDefinition,
    snapshot: MachineSnapshot<C>,
    previousActive: Set<StateId> = emptySet(),
    inspection: ChartInspection = ChartInspection.Unrequested,
    modifier: Modifier = Modifier,
) {
    var selection by remember(chart) { mutableStateOf<ChartSelection>(ChartSelection.Overview) }
    val positions = remember(chart) { chartPositions(chart) }
    val outline = MaterialTheme.colorScheme.outlineVariant
    val highlight = MaterialTheme.colorScheme.primary
    Column(modifier.testTag("statechart-panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(resource = Res.string.snapshot_counts, snapshot.revision, snapshot.commands.size, snapshot.timers.size))
        for (violation in inspection.violations) Text(stringResource(resource = Res.string.invariant_failed, violation.name), color = MaterialTheme.colorScheme.error)
        StateChartDiagram(chartNodes(positions, snapshot, previousActive), chartEdges(chart, positions, selection, inspection), highlight, outline) { selection = ChartSelection.Node(it) }
        for ((index, transition) in chart.transitions.withIndex()) {
            OutlinedButton(onClick = { selection = ChartSelection.Transition(TransitionId(index)) }, modifier = Modifier.testTag("chart-transition-$index")) {
                Text("T$index: ${transition.source} → ${transition.target} · ${transition.triggerLabel}")
            }
        }
        StateChartDetails(chart, snapshot, selection, inspection)
    }
}
