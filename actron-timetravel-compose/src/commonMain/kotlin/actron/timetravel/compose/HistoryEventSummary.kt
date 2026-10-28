package actron.timetravel.compose

import actron.timetravel.HistoryDetails
import actron.timetravel.compose.resources.Res
import actron.timetravel.compose.resources.changes_fields
import actron.timetravel.compose.resources.changes_fields_more
import actron.timetravel.compose.resources.no_other_field_changes

/** Bounded row preview; the selected event still exposes the original payload and fields. */
internal data class HistoryEventSummary(
    val action: String,
    val before: String,
    val after: String,
    val changes: HistoryChanges,
)

private val historyRowMetadata = setOf("active", "screen", "revision", "type")
private val historyPreviewWhitespace = Regex("\\s+")

internal fun historyEventSummary(
    details: HistoryDetails,
    unchanged: String,
): HistoryEventSummary {
    val changes = details.changes.filter { it.path !in historyRowMetadata }
    val action =
        details.input
            .substringBefore('(')
            .substringBefore('\n')
            .substringAfterLast('.')
            .ifBlank { details.source }
    val changePreview =
        HistoryChanges(
            fields = changes.take(n = 2).map { compactHistoryPreview(value = it.path, limit = 28) },
            remaining = (changes.size - 2).coerceAtLeast(minimumValue = 0),
        )
    val states = historyTransitionStates(details = details, unchanged = unchanged)
    return HistoryEventSummary(
        compactHistoryPreview(action, 64),
        compactHistoryPreview(states.before, 48),
        compactHistoryPreview(states.after, 48),
        changePreview,
    )
}

private fun compactHistoryPreview(
    value: String,
    limit: Int,
): String {
    val line = value.replace(historyPreviewWhitespace, " ").trim()
    return if (line.length <= limit) line else line.take(limit - 1) + "…"
}

internal data class HistoryChanges(
    val fields: List<String>,
    val remaining: Int,
)

@androidx.compose.runtime.Composable
internal fun HistoryChanges.resolve(): String =
    when {
        fields.isEmpty() -> {
            org.jetbrains.compose.resources
                .stringResource(resource = Res.string.no_other_field_changes)
        }

        remaining > 0 -> {
            org.jetbrains.compose.resources
                .stringResource(resource = Res.string.changes_fields_more, fields.joinToString(separator = ", "), remaining)
        }

        else -> {
            org.jetbrains.compose.resources
                .stringResource(resource = Res.string.changes_fields, fields.joinToString(separator = ", "))
        }
    }
