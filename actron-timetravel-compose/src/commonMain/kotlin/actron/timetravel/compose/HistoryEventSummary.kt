package actron.timetravel.compose

import actron.timetravel.HistoryDetails

/** Bounded row preview; the selected event still exposes the original payload and fields. */
internal data class HistoryEventSummary(
    val action: String,
    val before: String,
    val after: String,
    val changes: String,
)

private val historyRowMetadata = setOf("active", "screen", "revision", "type")
private val historyPreviewWhitespace = Regex("\\s+")

internal fun historyEventSummary(
    details: HistoryDetails,
    language: String = "en",
): HistoryEventSummary {
    val changes = details.changes.filter { it.path !in historyRowMetadata }
    val action =
        details.input
            .substringBefore('(')
            .substringBefore('\n')
            .substringAfterLast('.')
            .ifBlank { details.source }
    val changePreview =
        if (changes.isEmpty()) {
            HistoryText.NoOtherFieldChanges.forLanguage(language)
        } else {
            val fields = changes.take(2).joinToString(", ") { compactHistoryPreview(it.path, 28) }
            val remaining = (changes.size - 2).coerceAtLeast(0)
            val more = if (remaining > 0) " · ${HistoryText.MoreFields.forLanguage(language)} $remaining" else ""
            "${HistoryText.ChangedFields.forLanguage(language)}: $fields$more"
        }
    val states = historyTransitionStates(details, language)
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
