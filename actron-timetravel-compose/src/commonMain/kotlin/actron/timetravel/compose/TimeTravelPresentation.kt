@file:OptIn(actron.core.ExperimentalActronApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package actron.timetravel.compose

import actron.timetravel.inspect.InspectorText
import androidx.compose.ui.unit.dp

internal fun validPanelSelection(state: InspectorState): InspectorSelection {
    val selection = state.selection
    return if (selection is InspectorSelection.Position && selection.index in state.inspector.timeline.indices) selection else InspectorSelection.Overview
}

internal fun panelVertical(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
): Boolean = width < 400.dp && height >= 400.dp

internal fun panelCompleteness(state: InspectorState): List<String> =
    if (state.inspector.completeness.isComplete) {
        emptyList()
    } else {
        listOf(
            "Partial history: " +
                state.inspector.completeness.reasons
                    .joinToString("; ") { InspectorText.reason(it) },
        )
    }
