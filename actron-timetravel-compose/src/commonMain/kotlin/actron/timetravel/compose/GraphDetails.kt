package actron.timetravel.compose

import actron.statechart.StateId
import actron.statechart.machine.DurationProfile
import actron.statechart.machine.StateProfile
import actron.statechart.machine.TransitionProfile
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun GraphDetails(
    graph: HistoryGraph.Machine,
    layout: GraphLayout,
    selection: GraphInteraction,
    measurements: GraphMeasurements,
) {
    selection.selectedEdges.forEach { GraphTransitionDetails(graph, layout.edges[it], selection.profiling.value, measurements) }
    selection.selected.forEach { GraphStateDetails(graph, it, selection.profiling.value, measurements) }
    Text(historyText(HistoryText.GraphHint), Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun GraphTransitionDetails(
    graph: HistoryGraph.Machine,
    edge: GraphEdge,
    profiling: Boolean,
    measurements: GraphMeasurements,
) {
    val colors = MaterialTheme.colorScheme
    val transition = edge.transition
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(colors.surfaceContainerLow)
            .border(1.dp, colors.outlineVariant, MaterialTheme.shapes.medium)
            .heightIn(max = 180.dp)
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .testTag("graph-transition-details"),
    ) {
        Text("${historyText(HistoryText.Transition)} #${edge.id.index + 1}", style = MaterialTheme.typography.titleSmall, color = colors.primary)
        Spacer(Modifier.height(6.dp))
        if (graph.coverage is HistoryGraphCoverage.Recorded) {
            Text("${historyText(HistoryText.Executions)}: ${measurements.counts.getOrElse(edge.id) { 0 }}", Modifier.testTag("graph-transition-hits"), style = MaterialTheme.typography.bodySmall)
        }
        if (profiling) {
            for (profile in measurements.profiles) {
                profile.transitions.getOrElse(edge.id) { TransitionProfile() }.let { measured ->
                    Text("${historyText(HistoryText.ProfileExecutions)}: ${measured.hits}", style = MaterialTheme.typography.bodySmall)
                    ProfileDuration(HistoryText.InputQueue, measured.queue)
                    ProfileDuration(HistoryText.InputProcessing, measured.processing)
                }
            }
        }
        Text("${historyText(HistoryText.FromState)}: ${transition.source.value}", style = MaterialTheme.typography.bodySmall)
        Text("${historyText(HistoryText.ToState)}: ${transition.target.value}", style = MaterialTheme.typography.bodySmall)
        Text("${historyText(HistoryText.Action)}: ${transition.triggerLabel}", style = MaterialTheme.typography.bodySmall)
        Text("${historyText(HistoryText.Guard)}: ${transition.guard.displayLabel}", style = MaterialTheme.typography.bodySmall)
        Text("${historyText(HistoryText.Effect)}: ${transition.effect.displayLabel}", style = MaterialTheme.typography.bodySmall)
        Text("${historyText(HistoryText.TransitionKind)}: ${transition.kind}", style = MaterialTheme.typography.bodySmall)
        Text(historyText(HistoryText.DeclaredTransition), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun GraphStateDetails(
    graph: HistoryGraph.Machine,
    id: StateId,
    profiling: Boolean,
    measurements: GraphMeasurements,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(colors.surfaceContainerLow)
            .border(1.dp, colors.outlineVariant, MaterialTheme.shapes.medium)
            .heightIn(max = 140.dp)
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .testTag("graph-state-transitions"),
    ) {
        Text("${historyText(HistoryText.State)}: ${id.value}", style = MaterialTheme.typography.titleSmall, color = colors.primary)
        Spacer(Modifier.height(6.dp))
        if (profiling) {
            for (profile in measurements.profiles) {
                profile.states.getOrElse(id) { StateProfile() }.let { measured ->
                    Text("${historyText(HistoryText.Visits)}: ${measured.visits}", style = MaterialTheme.typography.bodySmall)
                    ProfileDuration(HistoryText.Residence, measured.residence)
                    ProfileDuration(HistoryText.CommandQueue, measured.commandQueue)
                    ProfileDuration(HistoryText.CommandExecution, measured.commandExecution)
                    Text("${historyText(HistoryText.CancelledCommands)}: ${measured.cancelledCommands}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        graph.definition.transitionsFrom(id).forEach { transition ->
            Text("${transition.triggerLabel}: ${transition.target.value}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProfileDuration(
    label: HistoryText,
    profile: DurationProfile,
) {
    Text(
        "${historyText(label)}: " + if (profile.samples == 0L) "—" else "${historyText(HistoryText.Average)} ${profile.average}, max ${profile.maximum} (n=${profile.samples})",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("profile-duration-${label.name}"),
    )
}
