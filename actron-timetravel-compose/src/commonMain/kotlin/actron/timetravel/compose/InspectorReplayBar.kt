package actron.timetravel.compose

import actron.timetravel.compose.resources.Res
import actron.timetravel.compose.resources.replay_back
import actron.timetravel.compose.resources.replay_forward
import actron.timetravel.compose.resources.replay_forward_disabled
import actron.timetravel.compose.resources.replay_position
import actron.timetravel.compose.resources.replay_verify
import actron.timetravel.inspect.Availability
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource

/** Back, forward, seek and verify over a [ReplayControls]; a disabled button says why. */
@Composable
internal fun InspectorReplayBar(
    replay: ReplayControls<*, *, *, *>,
    state: InspectorState,
    modifier: Modifier = Modifier,
) {
    var seeking by remember { mutableStateOf<SeekGesture>(SeekGesture.Idle) }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("replay-bar")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                replay.stepBackward()
                state.clearSelection()
            }, enabled = replay.canStepBackward, modifier = Modifier.testTag("replay-back")) { Text(stringResource(resource = Res.string.replay_back)) }
            OutlinedButton(onClick = {
                replay.stepForward()
                state.clearSelection()
            }, enabled = inspectorForwardAllowed(replay), modifier = Modifier.testTag("replay-forward")) { Text(stringResource(resource = Res.string.replay_forward)) }
            OutlinedButton(onClick = { replay.verify() }, modifier = Modifier.testTag("replay-verify")) { Text(stringResource(resource = Res.string.replay_verify)) }
            Text(stringResource(resource = Res.string.replay_position, replay.position, replay.length, replay.store), modifier = Modifier.padding(top = 12.dp).testTag("replay-position"))
        }
        Slider(
            value = if (seeking is SeekGesture.Dragging) (seeking as SeekGesture.Dragging).value else replay.position.toFloat(),
            onValueChange = { seeking = SeekGesture.Dragging(it) },
            onValueChangeFinished = {
                val gesture = seeking
                if (gesture is SeekGesture.Dragging) replay.seek(gesture.value.toInt())
                seeking = SeekGesture.Idle
                state.clearSelection()
            },
            valueRange = 0f..replay.length.toFloat().coerceAtLeast(1f),
            steps = (replay.length - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth().testTag("replay-seek"),
        )
        ForwardStepMessage(availability = replay.forwardAvailability, modifier = Modifier.testTag("replay-why"))
        ReplayVerdict(verification = replay.verification, modifier = Modifier.testTag("replay-verdict"))
    }
}
