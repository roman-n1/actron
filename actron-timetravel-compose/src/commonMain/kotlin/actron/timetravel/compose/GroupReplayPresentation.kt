@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.timetravel.compose

internal fun groupReplayRowSelected(
    replay: GroupReplayControls,
    index: Int,
): Boolean = replay.hasSelection && replay.selectedPosition().index == index
