@file:OptIn(actron.core.ExperimentalActronApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package actron.timetravel.compose

import actron.core.ExperimentalActronApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@ExperimentalActronApi
@Composable
internal fun GroupExperimentPanel(
    state: InspectorState,
    timeTravel: GroupTimeTravelControls,
    modifier: Modifier = Modifier,
) {
    val travel = timeTravel.travel
    PanelLayout(state, timeTravel.mode, timeTravel.replay.members, modifier, navigation = {
        if (travel is GroupTravel.Experiment) {
            TextButton(onClick = timeTravel::returnToReplay, modifier = Modifier.testTag("panel-return")) { Text("Return to recording") }
            Text("From step ${travel.position}", style = MaterialTheme.typography.labelMedium)
        } else {
            PanelGroupNavigation(timeTravel.replay)
            TextButton(onClick = timeTravel::branchHere, modifier = Modifier.testTag("panel-branch")) { Text("Experiment here") }
        }
    }) { expanded, vertical ->
        if (travel is GroupTravel.Experiment) {
            PanelBranchBody(state.filter, travel.branch, expanded)
        } else {
            PanelGroupBody(state.filter, timeTravel.replay, expanded, vertical)
        }
    }
}
