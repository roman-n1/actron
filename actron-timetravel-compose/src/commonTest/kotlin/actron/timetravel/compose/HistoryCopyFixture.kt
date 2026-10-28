package actron.timetravel.compose

internal fun summaryCopy(language: String = "en"): Map<HistoryText, String> =
    if (language.startsWith("ru")) {
        mapOf(HistoryText.NoOtherFieldChanges to "Других изменений полей нет", HistoryText.MoreFields to "ещё", HistoryText.ChangedFields to "Изменены поля", HistoryText.NoStateChange to "Без смены состояния")
    } else {
        mapOf(HistoryText.NoOtherFieldChanges to "No other field changes", HistoryText.MoreFields to "more:", HistoryText.ChangedFields to "Changed fields", HistoryText.NoStateChange to "No state change")
    }
