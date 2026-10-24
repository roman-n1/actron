@file:OptIn(actron.core.ExperimentalActronApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package actron.timetravel.compose

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun PanelNavigation(
    position: Int,
    length: Int,
    back: Boolean,
    forward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onVerify: () -> Unit,
) {
    TextButton(onClick = onBack, enabled = back, modifier = Modifier.testTag("panel-back")) { Text("Back") }
    Text("$position / $length", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 14.dp).testTag("panel-position"))
    TextButton(onClick = onForward, enabled = forward, modifier = Modifier.testTag("panel-forward")) { Text("Next") }
    TextButton(onClick = onVerify, modifier = Modifier.testTag("panel-verify")) { Text("Verify") }
}

@Composable
internal fun PanelGroupNavigation(replay: GroupReplayControls) = PanelNavigation(replay.position, replay.length, replay.canStepBackward, replay.canStepForward, replay::stepBackward, replay::stepForward, replay::verify)
