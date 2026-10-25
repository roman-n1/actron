@file:OptIn(actron.core.ExperimentalActronApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package actron.timetravel.compose

import actron.core.ExperimentalActronApi
import actron.timetravel.inspect.InspectorText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@ExperimentalActronApi
@Composable
internal fun JournalTimeTravelPanel(
    state: InspectorState,
    modifier: Modifier = Modifier,
) {
    PanelLayout(state, state.mode, state.inspector.stores.map { it.id }, modifier) { expanded, vertical ->
        val visible = state.visibleTimeline
        if (visible.isEmpty()) PanelEmpty()
        val selection = validPanelSelection(state)
        PanelTimeline(
            visible.map { it.index },
            if (selection is InspectorSelection.Position) selection.index else -1,
            vertical,
            label = { InspectorText.line(state.inspector.timeline[it]) },
            onSelect = state::select,
        )
        if (selection is InspectorSelection.Position) {
            val item = state.inspector.timeline[selection.index]
            Text(InspectorText.line(item), style = MaterialTheme.typography.bodySmall)
            if (expanded) PanelLines(InspectorText.detail(item))
        }
        if (expanded) PanelJournalStores(state)
    }
}

@Composable
private fun PanelJournalStores(state: InspectorState) {
    for (store in state.inspector.stores.filter { state.filter.includes(it.id) }) {
        Text("${store.id.value}: ${InspectorText.completeness(store.completeness)}", style = MaterialTheme.typography.bodySmall)
        for (reason in store.completeness.reasons) PanelProblem(InspectorText.reason(reason))
    }
}
