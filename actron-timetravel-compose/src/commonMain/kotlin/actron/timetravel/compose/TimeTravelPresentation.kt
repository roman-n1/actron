@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package actron.timetravel.compose

import actron.timetravel.compose.resources.Res
import actron.timetravel.compose.resources.partial_history_reasons
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

internal fun panelCompleteness(state: InspectorState): List<PanelText> =
    if (state.inspector.completeness.isComplete) {
        emptyList()
    } else {
        listOf(
            panelText(
                resource = Res.string.partial_history_reasons,
                state.inspector.completeness.reasons
                    .joinToString(separator = "; ") { InspectorText.reason(reason = it) },
            ),
        )
    }
