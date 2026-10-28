package actron.timetravel.compose

import actron.timetravel.HistoryDetails
import actron.timetravel.StateField

internal data class HistoryTransitionStates(
    val before: String,
    val after: String,
)

/** Compare before truncating so unchanged parallel regions cannot hide the transition. */
internal fun historyTransitionStates(
    details: HistoryDetails,
    unchanged: String,
): HistoryTransitionStates {
    val before = details.before.activeStates()
    val after = details.after.activeStates()
    val common = before.toSet().intersect(after.toSet())
    if (before.isNotEmpty() && common.size == before.size && common.size == after.size) {
        return HistoryTransitionStates(unchanged, unchanged)
    }
    return HistoryTransitionStates(
        before.filter { it !in common }.joinToString().ifEmpty { "—" },
        after.filter { it !in common }.joinToString().ifEmpty { "—" },
    )
}

private fun List<StateField>.activeStates(): List<String> =
    filter { it.path == "active" || it.path == "screen" }
        .flatMap { field ->
            // Active configurations are recorded with joinToString(); screen names remain opaque.
            if (field.path == "active") field.value.split(", ") else listOf(field.value)
        }.map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
