package actron.timetravel.compose

import actron.timetravel.HistoryEntry
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Selecting an entry previews its immutable snapshot, including after eviction.
 * A different timeline or recording clears source-local inspection state; reopening the same
 * source preserves it. The first attachment preserves a selection prepared by the host.
 */
@Stable
class HistoryPanelState<S : Any> {
    internal val inspection = HistoryInspection()
    private var source: HistorySourceBinding = HistorySourceBinding.Unbound

    /** First attachment preserves host-preselected entries; a different source starts fresh. */
    internal fun bindSource(next: Any) {
        val previous = source
        if (previous is HistorySourceBinding.Bound && previous.source != next) {
            clearSelection()
            inspection.resetSource()
        }
        source = HistorySourceBinding.Bound(next)
    }

    var selection: List<HistoryEntry<S>> by mutableStateOf(emptyList())
        private set
    var preview: List<HistoryEntry<S>> by mutableStateOf(emptyList())
        private set

    fun select(entry: HistoryEntry<S>) {
        selection.filter { it.id != entry.id }.forEach { inspection.depart(it.id) }
        selection = listOf(entry)
        preview = selection
    }

    internal fun clearSelection() {
        selection = emptyList()
        preview = emptyList()
        inspection.clearNavigation()
    }

    internal fun navigate(
        entries: List<HistoryEntry<S>>,
        backward: Boolean,
    ) {
        inspection.retain(entries.map { it.id }.toSet())
        val history = if (backward) inspection.back else inspection.forward
        if (history.isEmpty()) return
        val destination = history.last()
        val current = selection.map { inspection.location(it.id) }
        if (backward) {
            inspection.back = history.dropLast(1)
            inspection.forward = (inspection.forward + current).takeLast(50)
        } else {
            inspection.forward = history.dropLast(1)
            inspection.back = (inspection.back + current).takeLast(50)
        }
        inspection.restore(destination)
        entries.filter { it.id == destination.id }.forEach {
            selection = listOf(it)
            preview = selection
        }
    }

    fun previewSelected() {
        preview = selection
    }

    fun returnToLive() {
        preview = emptyList()
    }
}

private sealed interface HistorySourceBinding {
    data object Unbound : HistorySourceBinding

    data class Bound(
        val source: Any,
    ) : HistorySourceBinding
}
