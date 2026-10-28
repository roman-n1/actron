package actron.timetravel.compose

private val expectedHistoryLabels =
    mapOf(
        HistoryText.Event to ("Event" to "Событие"),
        HistoryText.Capacity to ("limit" to "лимит"),
        HistoryText.Events to ("events" to "событий"),
        HistoryText.FullState to ("Full state" to "Всё состояние"),
        HistoryText.OutsideWindow to ("Selected snapshot is outside the retained history window." to "Выбранный снимок находится за пределами сохранённого окна истории."),
        HistoryText.ChangedFields to ("Changed fields" to "Изменены поля"),
        HistoryText.NoOtherFieldChanges to ("No other field changes" to "Других изменений полей нет"),
    )

internal fun expectedHistoryText(
    text: HistoryText,
    language: String,
): String {
    val labels = expectedHistoryLabels.getValue(key = text)
    return if (language.startsWith(prefix = "ru")) labels.second else labels.first
}
