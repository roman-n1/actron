package actron.timetravel.compose

import actron.timetravel.HistoryEntry
import actron.timetravel.compose.resources.Res
import actron.timetravel.compose.resources.counter_live
import actron.timetravel.compose.resources.counter_selected
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

/** Event numbers follow the recording sequence, independently of filtering and capacity eviction. */
@androidx.compose.runtime.Composable
internal fun <S : Any> historyEventCounter(
    state: HistoryPanelState<S>,
    entries: List<HistoryEntry<S>>,
    capacity: Int,
): String {
    val numbers = historyCounterNumbers(state = state, entries = entries)
    return when (numbers) {
        is HistoryCounterNumbers.Live -> {
            org.jetbrains.compose.resources
                .stringResource(resource = actron.timetravel.compose.resources.Res.string.counter_live, numbers.latest, capacity)
        }

        is HistoryCounterNumbers.Selected -> {
            org.jetbrains.compose.resources
                .stringResource(resource = actron.timetravel.compose.resources.Res.string.counter_selected, numbers.selected, numbers.latest, capacity)
        }
    }
}

private sealed interface HistoryCounterNumbers {
    data class Live(
        val latest: Long,
    ) : HistoryCounterNumbers

    data class Selected(
        val selected: Long,
        val latest: Long,
    ) : HistoryCounterNumbers
}

private fun <S : Any> historyCounterNumbers(
    state: HistoryPanelState<S>,
    entries: List<HistoryEntry<S>>,
): HistoryCounterNumbers {
    val latest = entries.fold(initial = 0L) { id, entry -> maxOf(a = id, b = entry.id) }
    return if (state.preview.isEmpty()) {
        HistoryCounterNumbers.Live(latest = latest)
    } else {
        val selected = state.preview.first().id
        HistoryCounterNumbers.Selected(selected = selected, latest = maxOf(a = latest, b = selected))
    }
}
