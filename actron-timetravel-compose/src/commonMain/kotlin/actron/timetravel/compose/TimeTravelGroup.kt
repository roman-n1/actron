@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package actron.timetravel.compose

import actron.timetravel.compose.resources.Res
import actron.timetravel.compose.resources.before_first_input
import actron.timetravel.compose.resources.verified_inputs
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.selected
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PanelGroupBody(
    filter: StoreFilter,
    replay: GroupReplayControls,
    expanded: Boolean,
    vertical: Boolean,
) {
    PanelGroupStatus(replay)
    val visible =
        replay.session.recording.order
            .withIndex()
            .filter { filter.includes(it.value.store) }
    val selected = if (replay.hasSelection) replay.selectedPosition().index else -1
    if (visible.isEmpty()) PanelEmpty()
    PanelTimeline(visible.map { it.index }, selected, vertical, label = { index ->
        val step = replay.at(index)
        "${step.store.value} · ${step.recorded.input.panelLabel()}"
    }, onSelect = { replay.seek(it + 1) })
    if (replay.hasSelection) {
        val item = replay.selectedPosition()
        Text("${item.store.value}: ${item.before.configuration.active.names()} → ${item.after.configuration.active.names()}", style = MaterialTheme.typography.bodySmall)
        if (expanded) PanelRecorded(item.recorded, item.before, item.after)
    } else {
        Text(stringResource(resource = Res.string.before_first_input), style = MaterialTheme.typography.bodySmall)
    }
    if (expanded) {
        for (store in replay.members.filter { filter.includes(it) }) {
            PanelSnapshot(store, replay.snapshot(store))
            val checkpoint = replay.checkpoint(store)
            PanelLines(listOf("Clock: ${checkpoint.now.sinceStart}", "Running: ${checkpoint.lanes.running.keys}", "Queued: ${checkpoint.lanes.queued}", "Ending: ${checkpoint.ending.keys}", "Pending effects: ${checkpoint.effects}"))
        }
    }
}

/** Bottom docks get a horizontal history; tall, narrow sidebars get a bounded vertical list. */

@Composable
private fun PanelGroupStatus(replay: GroupReplayControls) {
    val movement = replay.movement
    if (movement is GroupReplayMovement.Diverged) PanelProblem(movement.mismatch.explain())
    val verification = replay.verification
    if (verification is GroupVerification.Checked) {
        if (verification.problems.isEmpty()) {
            PanelVerdict(stringResource(resource = Res.string.verified_inputs, replay.length))
        } else {
            for (problem in verification.problems) PanelProblem(problem.explain())
        }
    }
}
