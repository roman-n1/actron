@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package actron.timetravel.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A compact, embeddable inspector with a machine picker, input history and expandable details.
 * It uses the host's [MaterialTheme] and [modifier]; it owns no window, sheet, height or app UI.
 * Place it in a bottom dock, sheet or any other Compose container. A bounded height keeps the
 * controls above a scrollable body; an unbounded host gets content-sized layout.
 *
 * This overload observes [state]'s journal (including live updates from
 * [rememberLiveInspector]). Recording must be installed independently of panel visibility.
 * Journal selection is inspection only; use a replay overload to re-decide recorded inputs.
 */
@Composable
fun TimeTravelPanel(
    state: InspectorState,
    modifier: Modifier = Modifier,
) {
    JournalTimeTravelPanel(state, modifier)
}

/** Replays one machine offline. [ReplayControls.snapshot] can separately drive an app preview. */
@Composable
fun TimeTravelPanel(
    state: InspectorState,
    replay: ReplayControls<*, *, *, *>,
    modifier: Modifier = Modifier,
) {
    ReplayTimeTravelPanel(state, replay, modifier)
}

/** One global cursor for the group. Choosing a machine filters rows without moving any member. */
@Composable
fun TimeTravelPanel(
    state: InspectorState,
    groupReplay: GroupReplayControls,
    modifier: Modifier = Modifier,
) {
    GroupTimeTravelPanel(state, groupReplay, modifier)
}

/** The group's replay and isolated experiments, using the application's scripted branch inputs. */
@Composable
fun TimeTravelPanel(
    state: InspectorState,
    timeTravel: GroupTimeTravelControls,
    modifier: Modifier = Modifier,
) {
    GroupExperimentPanel(state, timeTravel, modifier)
}
