@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.timetravel.compose

import actron.core.ExperimentalActronApi
import actron.timetravel.inspect.Completeness
import actron.timetravel.inspect.InspectorText
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The inspector of a group's history (handoff §11): the mode and the completeness on top, the
 * Stores with their capabilities and reasons on the left, the timeline in the middle, the
 * selected position on the right, or the branch when there is one; stacked on a narrow
 * screen. Everything shown comes from the [actron.timetravel.inspect.Inspector] in [state];
 * nothing here runs a Store, a [replay] moves a `ReplaySession` that decides and compares,
 * never executes, and a [branch] drives a `GroupBranch` that decides with the pure machines.
 * With a replay, the position panel draws the machine's definition as Mermaid with the
 * replay's active states highlighted.
 *
 * @param state The inspector and the selection
 * @param replay The replay of one Store's recording to drive, when the screen is in [InspectorMode.Replay]
 * @param branch The branch to drive, when the screen is in [InspectorMode.Branch]
 */
@ExperimentalActronApi
@Composable
fun InspectorScreen(
    state: InspectorState,
    modifier: Modifier = Modifier,
) = InspectorScreenContent(
    state,
    state.mode,
    modifier,
    navigation = {},
    timeline = { TimelinePanel(state, it) },
    widePosition = { PositionPanel(state, it) },
    stackedPosition = { PositionPanel(state, it) },
)

@ExperimentalActronApi
@Composable
fun InspectorScreen(
    state: InspectorState,
    replay: ReplayControls<*, *, *, *>,
    modifier: Modifier = Modifier,
) = InspectorScreenContent(
    state,
    state.mode,
    modifier,
    navigation = { ReplayBar(replay, state) },
    timeline = { TimelinePanel(state, it) },
    widePosition = { PositionPanel(state, replay, it) },
    stackedPosition = { PositionPanel(state, replay, it) },
)

@ExperimentalActronApi
@Composable
fun InspectorScreen(
    state: InspectorState,
    branch: BranchControls,
    modifier: Modifier = Modifier,
) = InspectorScreenContent(
    state,
    state.mode,
    modifier,
    navigation = {},
    timeline = { TimelinePanel(state, it) },
    widePosition = { BranchPanel(branch, it) },
    stackedPosition = { PositionPanel(state, it) },
    afterPosition = { BranchPanel(branch, it) },
)

/** A group's recording on one cursor; all members share one global position. */
@ExperimentalActronApi
@Composable
fun InspectorScreen(
    state: InspectorState,
    groupReplay: GroupReplayControls,
    modifier: Modifier = Modifier,
) = InspectorScreenContent(
    state,
    state.mode,
    modifier,
    navigation = { GroupReplayBar(groupReplay) },
    timeline = { GroupTimelinePanel(groupReplay, state.filter, it) },
    widePosition = { GroupPositionPanel(groupReplay, it) },
    stackedPosition = { GroupPositionPanel(groupReplay, it) },
)

@ExperimentalActronApi
@Composable
fun InspectorScreen(
    state: InspectorState,
    groupReplay: GroupReplayControls,
    branch: BranchControls,
    modifier: Modifier = Modifier,
) = InspectorScreenContent(
    state,
    state.mode,
    modifier,
    navigation = { GroupReplayBar(groupReplay) },
    timeline = { GroupTimelinePanel(groupReplay, state.filter, it) },
    widePosition = { BranchPanel(branch, it) },
    stackedPosition = { GroupPositionPanel(groupReplay, it) },
    afterPosition = { BranchPanel(branch, it) },
)

/** Replay, experiment from the current group checkpoint, then return to recorded history. */
@ExperimentalActronApi
@Composable
fun InspectorScreen(
    state: InspectorState,
    timeTravel: GroupTimeTravelControls,
    modifier: Modifier = Modifier,
) {
    val travel = timeTravel.travel
    if (travel is GroupTravel.Experiment) {
        InspectorScreenContent(
            state,
            timeTravel.mode,
            modifier,
            showTimeline = false,
            navigation = { GroupTimeTravelBar(timeTravel) },
            timeline = { GroupTimelinePanel(timeTravel.replay, state.filter, it) },
            widePosition = { BranchPanel(travel.branch, it) },
            stackedPosition = { GroupPositionPanel(timeTravel.replay, it) },
            afterPosition = { BranchPanel(travel.branch, it) },
        )
    } else {
        InspectorScreenContent(
            state,
            timeTravel.mode,
            modifier,
            navigation = {
                GroupTimeTravelBar(timeTravel)
                GroupReplayBar(timeTravel.replay)
            },
            timeline = { GroupTimelinePanel(timeTravel.replay, state.filter, it) },
            widePosition = { GroupPositionPanel(timeTravel.replay, it) },
            stackedPosition = { GroupPositionPanel(timeTravel.replay, it) },
        )
    }
}

@ExperimentalActronApi
@Composable
fun ModeBar(
    state: InspectorState,
    modifier: Modifier = Modifier,
) = ModeBarContent(state, state.mode, modifier)

@ExperimentalActronApi
@Composable
fun StoresPanel(
    state: InspectorState,
    modifier: Modifier = Modifier,
) = InspectorStores(state, modifier)

@ExperimentalActronApi
@Composable
fun TimelinePanel(
    state: InspectorState,
    modifier: Modifier = Modifier,
) = InspectorTimeline(state, modifier)

@ExperimentalActronApi
@Composable
fun PositionPanel(
    state: InspectorState,
    modifier: Modifier = Modifier,
) = InspectorPosition(state, modifier) {}

@ExperimentalActronApi
@Composable
fun PositionPanel(
    state: InspectorState,
    replay: ReplayControls<*, *, *, *>,
    modifier: Modifier = Modifier,
) = InspectorPosition(state, modifier) { InspectorReplayPosition(replay) }

@ExperimentalActronApi
@Composable
fun DefinitionPanel(
    mermaid: String,
    title: String,
    tag: String,
    modifier: Modifier = Modifier,
) = InspectorDefinition(mermaid, title, tag, modifier)

@OptIn(ExperimentalLayoutApi::class)
@ExperimentalActronApi
@Composable
fun BranchPanel(
    branch: BranchControls,
    modifier: Modifier = Modifier,
) = InspectorBranch(branch, modifier)

@ExperimentalActronApi
@Composable
fun ReplayBar(
    replay: ReplayControls<*, *, *, *>,
    state: InspectorState,
    modifier: Modifier = Modifier,
) = InspectorReplayBar(replay, state, modifier)

/** For a screen that shows a completeness elsewhere. */
@ExperimentalActronApi
fun Completeness.label(): String = InspectorText.completeness(this)
