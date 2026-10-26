package actron.timetravel.compose

import actron.timetravel.HistoryDetails

/** Bounded row preview; the selected event still exposes the original payload and fields. */
internal data class HistoryEventSummary(
    val action: String,
    val before: String,
    val after: String,
    val changes: String,
    val moreChanges: Int,
)

private val historyRowMetadata = setOf("active", "screen", "revision", "type")
private val historyPreviewWhitespace = Regex("\\s+")

internal fun historyEventSummary(details: HistoryDetails): HistoryEventSummary {
    val changes = details.changes.filter { it.path !in historyRowMetadata }
    val action =
        details.input
            .substringBefore('(')
            .substringBefore('\n')
            .substringAfterLast('.')
            .ifBlank { details.source }
    return HistoryEventSummary(
        compactHistoryPreview(action, 64),
        compactHistoryPreview(details.activeBefore(), 48),
        compactHistoryPreview(details.activeAfter(), 48),
        changes.take(2).joinToString("\n") { "${it.path}: ${compactHistoryPreview(it.before, 28)} → ${compactHistoryPreview(it.after, 28)}" },
        (changes.size - 2).coerceAtLeast(0),
    )
}

private fun compactHistoryPreview(
    value: String,
    limit: Int,
): String {
    val line = value.replace(historyPreviewWhitespace, " ").trim()
    return if (line.length <= limit) line else line.take(limit - 1) + "…"
}
