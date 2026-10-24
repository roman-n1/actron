package actron.timetravel.compose

import actron.timetravel.HistoryEntry
import androidx.compose.runtime.MutableState

/** UI state owns its switch; composables send an intent instead of rebuilding boolean rules. */
internal fun MutableState<Boolean>.toggle() {
    value = !value
}

internal fun historyAllowsContinuation(readOnly: Boolean): Boolean = !readOnly

internal fun <S : Any> historyModeAvailable(
    state: HistoryPanelState<S>,
    visible: List<HistoryEntry<S>>,
): Boolean = state.selection.isNotEmpty() || visible.isNotEmpty()

internal fun <S : Any> historyContinuationEnabled(
    state: HistoryPanelState<S>,
    entry: HistoryEntry<S>,
    capability: HistoryContinuation,
): Boolean = state.preview.any { it.id == entry.id } && capability is HistoryContinuation.Available

internal fun historyModeSelected(
    history: Boolean,
    option: Boolean,
): Boolean = history == option

internal fun historyEventSelected(inspection: HistoryInspection): Boolean = !inspection.graph

internal enum class HistoryPanelPlacement { Wide, ListOnly, Selected }

internal fun historyPanelPlacement(
    narrow: Boolean,
    hasSelection: Boolean,
): HistoryPanelPlacement =
    when {
        !narrow -> HistoryPanelPlacement.Wide
        hasSelection -> HistoryPanelPlacement.Selected
        else -> HistoryPanelPlacement.ListOnly
    }
