package actron.timetravel.compose

import actron.timetravel.HistoryEntry

internal fun historyChangesSelected(inspection: HistoryInspection): Boolean = !inspection.fullState

internal fun historyFieldsRestricted(inspection: HistoryInspection): Boolean = inspection.fieldQuery.isNotBlank() || inspection.focus is HistoryFieldFocus.Path

internal fun historyFieldExpanded(
    inspection: HistoryInspection,
    expanded: Boolean,
): Boolean = inspection.fullState || expanded

internal data class HistoryFieldNavigation<S : Any>(
    val previous: List<HistoryEntry<S>>,
    val next: List<HistoryEntry<S>>,
)

/** Field navigation stays inside the same machine and searches the retained event window. */
internal fun <S : Any> historyFieldNavigation(
    entries: List<HistoryEntry<S>>,
    entry: HistoryEntry<S>,
    path: String,
): HistoryFieldNavigation<S> {
    val changes = entries.filter { it.machine.stableId == entry.machine.stableId && historyFieldChanges(it, path) }
    return HistoryFieldNavigation(changes.filter { it.id < entry.id }, changes.filter { it.id > entry.id })
}

internal fun historyComparisonFor(
    comparison: HistoryComparison,
    entry: HistoryEntry<*>,
): HistoryComparison = if (comparison is HistoryComparison.Against && comparison.machine == entry.machine.stableId) comparison else HistoryComparison.PreviousEvent
