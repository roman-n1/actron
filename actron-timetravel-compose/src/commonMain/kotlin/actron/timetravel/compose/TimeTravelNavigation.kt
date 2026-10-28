@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package actron.timetravel.compose

import actron.timetravel.compose.resources.Res
import actron.timetravel.compose.resources.navigation_next
import actron.timetravel.compose.resources.navigation_verify
import actron.timetravel.compose.resources.panel_back
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource

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
    TextButton(onClick = onBack, enabled = back, modifier = Modifier.testTag("panel-back")) { Text(stringResource(resource = Res.string.panel_back)) }
    Text("$position / $length", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 14.dp).testTag("panel-position"))
    TextButton(onClick = onForward, enabled = forward, modifier = Modifier.testTag("panel-forward")) { Text(stringResource(resource = Res.string.navigation_next)) }
    TextButton(onClick = onVerify, modifier = Modifier.testTag("panel-verify")) { Text(stringResource(resource = Res.string.navigation_verify)) }
}

@Composable
internal fun PanelGroupNavigation(replay: GroupReplayControls) = PanelNavigation(replay.position, replay.length, replay.canStepBackward, replay.canStepForward, replay::stepBackward, replay::stepForward, replay::verify)
