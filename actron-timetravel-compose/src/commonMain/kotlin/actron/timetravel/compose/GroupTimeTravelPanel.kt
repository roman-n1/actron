@file:OptIn(actron.core.ExperimentalActronApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package actron.timetravel.compose

import actron.core.ExperimentalActronApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@ExperimentalActronApi
@Composable
internal fun GroupTimeTravelPanel(
    state: InspectorState,
    groupReplay: GroupReplayControls,
    modifier: Modifier = Modifier,
) {
    PanelLayout(state, InspectorMode.Replay, groupReplay.members, modifier, navigation = { PanelGroupNavigation(groupReplay) }) { expanded, vertical ->
        PanelGroupBody(state.filter, groupReplay, expanded, vertical)
    }
}
