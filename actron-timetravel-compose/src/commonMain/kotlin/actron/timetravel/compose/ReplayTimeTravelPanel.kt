@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package actron.timetravel.compose

import actron.timetravel.compose.resources.Res
import actron.timetravel.compose.resources.explain_next
import actron.timetravel.inspect.Availability
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ReplayTimeTravelPanel(
    state: InspectorState,
    replay: ReplayControls<*, *, *, *>,
    modifier: Modifier = Modifier,
) {
    PanelLayout(state, InspectorMode.Replay, listOf(replay.store), modifier, navigation = {
        PanelNavigation(replay.position, replay.length, replay.canStepBackward, replay.canStepForward, replay::stepBackward, replay::stepForward, replay::verify)
    }) { expanded, vertical ->
        val movement = replay.movement
        if (movement is ReplayMovement.Diverged) PanelProblem(movement.description)
        val verification = replay.verification
        ReplayVerdict(verification = verification, modifier = Modifier.testTag("panel-verdict"))
        if (replay.length == 0) PanelEmpty()
        val selected = if (movement is ReplayMovement.Diverged) movement.position else replay.position - 1
        PanelTimeline(
            replay.session.recording.steps.indices
                .toList(),
            selected,
            vertical,
            label = {
                replay.session.recording.steps[it]
                    .input
                    .panelLabel()
            },
            onSelect = { replay.seek(it + 1) },
        )
        Text("${replay.store.value} · ${replay.snapshot.configuration.active.map { it.value }.sorted().joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
        if (expanded) PanelReplayDetails(replay)
    }
}

@Composable
private fun PanelReplayDetails(replay: ReplayControls<*, *, *, *>) {
    val movement = replay.movement
    PanelSnapshot(replay.store, replay.snapshot)
    val index = if (movement is ReplayMovement.Diverged) movement.position else replay.position - 1
    if (index in replay.session.recording.steps.indices) PanelRecorded(replay.session.recording.steps[index], replay.session.recording.snapshotAt(index), replay.session.recording.snapshotAt(index + 1))
    TextButton(onClick = replay::explainNext) { Text(stringResource(resource = Res.string.explain_next)) }
    StateChartPanel(replay.session.machine.chart, replay.snapshot, inspection = replay.inspection)
}
