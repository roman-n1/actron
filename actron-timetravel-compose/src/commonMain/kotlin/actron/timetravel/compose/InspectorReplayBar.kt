@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.timetravel.compose

import actron.core.ExperimentalActronApi
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

/** Back, forward, seek and verify over a [ReplayControls]; a disabled button says why. */
@ExperimentalActronApi
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
            }, enabled = replay.canStepBackward, modifier = Modifier.testTag("replay-back")) { Text("◀ back") }
            OutlinedButton(onClick = {
                replay.stepForward()
                state.clearSelection()
            }, enabled = inspectorForwardAllowed(replay), modifier = Modifier.testTag("replay-forward")) { Text("forward ▶") }
            OutlinedButton(onClick = { replay.verify() }, modifier = Modifier.testTag("replay-verify")) { Text("verify") }
            Text("${replay.position} / ${replay.length} of ${replay.store}", modifier = Modifier.padding(top = 12.dp).testTag("replay-position"))
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
        val availability = replay.forwardAvailability
        if (availability is Availability.Unavailable) {
            for (why in availability.reasons) {
                Text("forward disabled: $why", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.testTag("replay-why"))
            }
        }
        val verification = replay.verification
        if (verification is ReplayVerification.Checked) {
            Text(verification.verdict, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("replay-verdict"))
        }
    }
}
