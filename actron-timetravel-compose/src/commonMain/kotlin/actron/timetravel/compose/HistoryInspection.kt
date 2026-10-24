package actron.timetravel.compose

import actron.timetravel.HistoryEntry
import actron.timetravel.StateChange
import actron.timetravel.StateField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal sealed interface HistoryMachineFilter {
    data object All : HistoryMachineFilter

    data class Machine(
        val stableId: String,
    ) : HistoryMachineFilter
}

internal sealed interface HistoryFieldFocus {
    data object All : HistoryFieldFocus

    data class Path(
        val path: String,
    ) : HistoryFieldFocus
}

internal sealed interface HistoryComparison {
    data object PreviousEvent : HistoryComparison

    // Keep only display fields, never another executable/application snapshot.
    data class Against(
        val id: Long,
        val machine: String,
        val fields: List<StateField>,
    ) : HistoryComparison
}

internal data class HistoryLocation(
    val id: Long,
    val filter: HistoryMachineFilter,
    val query: String,
    val bookmarksOnly: Boolean,
    val graph: Boolean,
    val focus: HistoryFieldFocus,
    val fieldQuery: String,
    val fullState: Boolean,
)

/** Presentation state. Navigation and bookmarks retain IDs, not evicted snapshots. */
internal class HistoryInspection {
    var filter: HistoryMachineFilter by mutableStateOf(HistoryMachineFilter.All)
    var query: String by mutableStateOf("")
    var bookmarksOnly: Boolean by mutableStateOf(false)
    var bookmarks: Set<Long> by mutableStateOf(emptySet())
    var graph: Boolean by mutableStateOf(false)
    var focus: HistoryFieldFocus by mutableStateOf(HistoryFieldFocus.All)
    var fieldQuery: String by mutableStateOf("")
    var fullState: Boolean by mutableStateOf(false)
    var comparison: HistoryComparison by mutableStateOf(HistoryComparison.PreviousEvent)
    var back: List<HistoryLocation> by mutableStateOf(emptyList())
    var forward: List<HistoryLocation> by mutableStateOf(emptyList())

    fun changes(entry: HistoryEntry<*>): List<StateChange> {
        val before =
            when (val selected = comparison) {
                HistoryComparison.PreviousEvent -> entry.details.before
                is HistoryComparison.Against -> if (selected.machine == entry.machine.stableId) selected.fields else entry.details.before
            }
        return historyChanges(before, entry.details.after, fullState || focus is HistoryFieldFocus.Path).filter { change ->
            val focused =
                when (val selected = focus) {
                    HistoryFieldFocus.All -> true
                    is HistoryFieldFocus.Path -> selected.path == change.path
                }
            focused && change.path.contains(fieldQuery.trim(), ignoreCase = true)
        }
    }

    fun location(id: Long): HistoryLocation = HistoryLocation(id, filter, query, bookmarksOnly, graph, focus, fieldQuery, fullState)

    fun restore(location: HistoryLocation) {
        filter = location.filter
        query = location.query
        bookmarksOnly = location.bookmarksOnly
        graph = location.graph
        focus = location.focus
        fieldQuery = location.fieldQuery
        fullState = location.fullState
    }

    fun depart(id: Long) {
        back = (back + location(id)).takeLast(50)
        forward = emptyList()
    }

    fun retain(ids: Set<Long>) {
        bookmarks = bookmarks.intersect(ids)
        back = back.filter { it.id in ids }
        forward = forward.filter { it.id in ids }
    }

    fun clearNavigation() {
        back = emptyList()
        forward = emptyList()
    }

    fun toggleBookmark(id: Long) {
        bookmarks = if (id in bookmarks) bookmarks - id else bookmarks + id
    }

    fun reveal() {
        filter = HistoryMachineFilter.All
        query = ""
        bookmarksOnly = false
    }

    fun includes(entry: HistoryEntry<*>): Boolean {
        val machineMatches =
            when (val selected = filter) {
                HistoryMachineFilter.All -> true
                is HistoryMachineFilter.Machine -> entry.machine.stableId == selected.stableId
            }
        return machineMatches && (!bookmarksOnly || entry.id in bookmarks) && historyMatches(entry, query)
    }
}

internal fun historyMatches(
    entry: HistoryEntry<*>,
    query: String,
): Boolean {
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return true
    val details = entry.details
    val text =
        buildString {
            append("#${entry.id} ${entry.machine.name} ${entry.machine.stableId} ")
            append("${details.input} ${details.source} ${details.outcome} ")
            details.changes.forEach { append("${it.path} ${it.before} ${it.after} ") }
        }
    return terms.all { term ->
        if (term.startsWith("#")) term == "#${entry.id}" else text.contains(term, ignoreCase = true)
    }
}

/** Use recorded before/after, including the first retained event; never infer changes across a gap. */
internal fun historyFieldChanges(
    entry: HistoryEntry<*>,
    path: String,
): Boolean = entry.details.changes.any { it.path == path }

internal fun historyChanges(
    before: List<StateField>,
    after: List<StateField>,
    full: Boolean,
): List<StateChange> {
    val left = before.associate { it.path to it.value }
    val right = after.associate { it.path to it.value }
    return (left.keys + right.keys)
        .filter { full || left[it] != right[it] }
        .map { StateChange(it, left.getOrElse(it) { "<absent>" }, right.getOrElse(it) { "<absent>" }) }
}
