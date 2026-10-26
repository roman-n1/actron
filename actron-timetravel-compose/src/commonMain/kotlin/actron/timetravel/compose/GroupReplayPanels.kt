package actron.timetravel.compose

import actron.timetravel.RecordedStep
import actron.timetravel.compose.resources.Res
import actron.timetravel.compose.resources.before_first_input
import actron.timetravel.compose.resources.checkpoint_clock_timers
import actron.timetravel.compose.resources.checkpoint_commands
import actron.timetravel.compose.resources.checkpoint_effects
import actron.timetravel.compose.resources.commands_timers_effects
import actron.timetravel.compose.resources.definition_at
import actron.timetravel.compose.resources.entered_exited
import actron.timetravel.compose.resources.group_position
import actron.timetravel.compose.resources.group_recording
import actron.timetravel.compose.resources.group_state
import actron.timetravel.compose.resources.input_failed
import actron.timetravel.compose.resources.input_ignored
import actron.timetravel.compose.resources.recorded_after
import actron.timetravel.compose.resources.recorded_before
import actron.timetravel.compose.resources.replay_back
import actron.timetravel.compose.resources.replay_forward
import actron.timetravel.compose.resources.snapshot_active_context
import actron.timetravel.compose.resources.snapshot_revision
import actron.timetravel.compose.resources.transitions_value
import actron.timetravel.compose.resources.verify_group
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource

/** Movement and validation of the whole group, independent of the timeline's Store filter. */
@Composable
fun GroupReplayBar(
    replay: GroupReplayControls,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(8.dp).testTag("group-replay-bar")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = replay::stepBackward, enabled = replay.canStepBackward, modifier = Modifier.testTag("group-replay-back")) { Text(stringResource(resource = Res.string.replay_back)) }
            OutlinedButton(onClick = replay::stepForward, enabled = replay.canStepForward, modifier = Modifier.testTag("group-replay-forward")) { Text(stringResource(resource = Res.string.replay_forward)) }
            OutlinedButton(onClick = replay::verify, modifier = Modifier.testTag("group-replay-verify")) { Text(stringResource(resource = Res.string.verify_group)) }
            Text(stringResource(resource = Res.string.group_position, replay.position, replay.length), modifier = Modifier.padding(top = 12.dp).testTag("group-replay-position"))
        }
        GroupReplaySeek(replay)
        ForwardStepMessage(availability = replay.forwardAvailability, modifier = Modifier.testTag("group-replay-why"), color = MaterialTheme.colorScheme.error)
        GroupReplayVerdict(replay)
    }
}

/** The recording's inputs in their global order; a click seeks after that input. */
@Composable
fun GroupTimelinePanel(
    replay: GroupReplayControls,
    filter: StoreFilter = StoreFilter.All,
    modifier: Modifier = Modifier,
) {
    val visible =
        replay.session.recording.order
            .withIndex()
            .filter { filter.includes(it.value.store) }
    Column(modifier = modifier.padding(8.dp)) {
        Text(stringResource(resource = Res.string.group_recording, visible.size), style = MaterialTheme.typography.titleSmall)
        LazyColumn(modifier = Modifier.fillMaxSize().testTag("group-timeline")) {
            items(visible, key = { it.index }) { indexed ->
                val item = replay.at(indexed.index)
                Text(
                    "#${item.index} ${item.store.value}: ${item.recorded.input}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(if (groupReplayRowSelected(replay, item.index)) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { replay.seek(item.index + 1) }
                            .padding(vertical = 4.dp)
                            .testTag("group-step-${item.index}"),
                )
            }
        }
    }
}

/** Expected transition details and every member's checkpoint at the group's current prefix. */
@Composable
fun GroupPositionPanel(
    replay: GroupReplayControls,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
        Column(modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("group-position")) {
            Text(stringResource(resource = Res.string.group_state, replay.position), style = MaterialTheme.typography.titleSmall)
            if (replay.hasSelection) {
                val item = replay.selectedPosition()
                Text("#${item.index} ${item.store.value}: ${item.recorded.input}")
                Text(stringResource(resource = Res.string.recorded_before, item.before.context), fontFamily = FontFamily.Monospace)
                Text(stringResource(resource = Res.string.recorded_after, item.after.context), fontFamily = FontFamily.Monospace)
                Text(
                    stringResource(
                        resource = Res.string.entered_exited,
                        item.diff.entered
                            .map { it.value }
                            .sorted(),
                        item.diff.exited
                            .map { it.value }
                            .sorted(),
                    ),
                )
                when (val step = item.recorded) {
                    is RecordedStep.Committed<*, *, *, *> -> {
                        Text(stringResource(resource = Res.string.transitions_value, step.decision.transitions))
                        Text(stringResource(resource = Res.string.commands_timers_effects, step.decision.commands, step.decision.timersScheduled, step.decision.effects))
                    }

                    is RecordedStep.Ignored<*, *, *, *> -> {
                        Text(stringResource(resource = Res.string.input_ignored, step.reason))
                    }

                    is RecordedStep.Failed<*, *, *, *> -> {
                        Text(stringResource(resource = Res.string.input_failed, step.failure))
                    }
                }
            } else {
                Text(stringResource(resource = Res.string.before_first_input))
            }
        }
        for (store in replay.members) {
            val snapshot = replay.snapshot(store)
            val checkpoint = replay.checkpoint(store)
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .semantics(mergeDescendants = true) {}
                        .testTag("group-member-${store.value}"),
            ) {
                Text(stringResource(resource = Res.string.snapshot_revision, store.value, snapshot.revision), fontWeight = FontWeight.Bold)
                Text(
                    stringResource(
                        resource = Res.string.snapshot_active_context,
                        snapshot.configuration.active
                            .map { it.value }
                            .sorted(),
                        snapshot.context,
                    ),
                    fontFamily = FontFamily.Monospace,
                )
                Text(stringResource(resource = Res.string.checkpoint_clock_timers, checkpoint.now.sinceStart, snapshot.timers.keys))
                Text(stringResource(resource = Res.string.checkpoint_commands, checkpoint.lanes.running.keys, checkpoint.lanes.queued, checkpoint.ending.keys))
                Text(stringResource(resource = Res.string.checkpoint_effects, checkpoint.effects))
            }
            DefinitionPanel(replay.mermaid(store), stringResource(resource = Res.string.definition_at, store.value, replay.position), "group-definition-${store.value}")
        }
    }
}

/** A slider follows the cursor until the viewer starts dragging it. */
internal sealed interface SeekGesture {
    data object Idle : SeekGesture

    data class Dragging(
        val value: Float,
    ) : SeekGesture
}

@Composable
private fun GroupReplaySeek(replay: GroupReplayControls) {
    var seeking by remember(replay) { mutableStateOf<SeekGesture>(SeekGesture.Idle) }
    Slider(
        value = if (seeking is SeekGesture.Dragging) (seeking as SeekGesture.Dragging).value else replay.position.toFloat(),
        onValueChange = { seeking = SeekGesture.Dragging(it) },
        onValueChangeFinished = {
            val current = seeking
            if (current is SeekGesture.Dragging) replay.seek(current.value.toInt())
            seeking = SeekGesture.Idle
        },
        valueRange = 0f..replay.length.toFloat().coerceAtLeast(1f),
        steps = (replay.length - 1).coerceAtLeast(0),
        enabled = replay.length > 0,
        modifier = Modifier.testTag("group-replay-seek"),
    )
}

@Composable
private fun GroupReplayVerdict(replay: GroupReplayControls) {
    val verification = replay.verification
    if (verification is GroupVerification.Checked) {
        val problems = verification.problems
        val verdict = if (problems.isEmpty()) "no divergence in ${replay.length} group steps" else problems.joinToString("\n") { it.explain() }
        Text(verdict, modifier = Modifier.testTag("group-replay-verdict"))
    }
}
