package actron.timetravel.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.intl.Locale

/** UI copy only. Machine identifiers, action payloads and diagnostics remain verbatim. */
internal enum class HistoryText(
    val english: String,
    val russian: String,
) {
    Title("Actron Inspector", "Инспектор Actron"),
    ImportedRecording("Imported recording · preview only. Continuing is unavailable: this file contains display snapshots and is not connected to the live session.", "Импортированная запись · только просмотр. Продолжить отсюда нельзя: файл содержит снимки экранов и не подключён к рабочему сеансу."),
    Session("State machines · current session", "Машины состояний · текущий запуск"),
    AllMachines("All machines", "Все машины"),
    PreviousInstance("Previous instance", "Предыдущий экземпляр"),
    Timeline("TIMELINE", "ИСТОРИЯ"),
    Events("events", "событий"),
    Empty("Events appear here as you use the app.", "События появятся здесь во время работы приложения."),
    Explore("Explore a transition", "Посмотрите переход"),
    SelectHint("Select an event to see its action, compare states and show that moment in the app.", "Выберите событие, чтобы увидеть действие, сравнить состояния и перейти к этому моменту в приложении."),
    Event("Event", "Событие"),
    Graph("Graph", "Граф"),
    Coverage("Transition coverage", "Покрытие переходов"),
    CoverageLegend("solid: executed · dashed: not executed", "сплошная: выполнен · пунктир: не выполнен"),
    Executions("Executions", "Выполнений"),
    Profile("Profile", "Профиль"),
    ProfileExecutions("Executions in this window", "Выполнений за период замера"),
    ProfileWindow("Completed samples before this event · warmer edges: slower input processing · per executor", "Завершённые замеры до события · краснее — дольше обработка · за жизнь экземпляра"),
    ProfileDropped("Dropped timing correlations", "Утраченные связи замеров"),
    InputQueue("Input queue", "Очередь действий"),
    InputProcessing("Input processing (shared by its transitions)", "Обработка действия (общая для его переходов)"),
    CommandQueue("Command queue", "Очередь команд"),
    CommandExecution("Command handler lifetime", "Время выполнения команды"),
    Residence("Completed state residence", "Завершённое пребывание в состоянии"),
    Visits("Entries", "Входов"),
    CancelledCommands("Cancelled running commands", "Отменено выполнявшихся команд"),
    Average("avg", "среднее"),
    Transition("Transition", "Переход"),
    State("State", "Состояние"),
    FromState("From", "Из состояния"),
    ToState("To", "В состояние"),
    Guard("Guard", "Условие"),
    Effect("Effect", "Обработчик"),
    TransitionKind("Kind", "Тип"),
    DeclaredTransition("Declared transition; its guard is not evaluated during inspection.", "Переход из описания машины; условие при просмотре не вычисляется."),
    NoGraph("No graph supplied for this machine.", "Для этой машины граф не предоставлен."),
    Fit("Fit", "Вписать"),
    ZoomIn("Zoom in", "Увеличить"),
    ZoomOut("Zoom out", "Уменьшить"),
    GraphHint("Drag to pan · pinch or Ctrl + scroll to zoom · select a state or action for details", "Перетаскивание — перемещение · щипок или Ctrl + прокрутка — масштаб · выбор состояния или действия — подробности"),
    ActiveStates("Active states", "Активные состояния"),
    Changes("Changes", "Изменения"),
    FullState("Full state", "Всё состояние"),
    Field("FIELD", "ПОЛЕ"),
    Before("BEFORE", "ДО"),
    After("AFTER", "ПОСЛЕ"),
    Unchanged("State unchanged", "Состояние не изменилось"),
    Continue("Continue here", "Продолжить отсюда"),
    Unsafe("Pending effects prevent safe continuation.", "Незавершённые эффекты препятствуют безопасному продолжению."),
    ContinueHint("Continue from this moment. Later history will be removed.", "Продолжите с этого момента. Более поздняя история будет удалена."),
    SelectToView("Select an event to view it in the app.", "Выберите событие, чтобы показать его в приложении."),
    History("History", "История"),
    Live("Live", "Сейчас"),
    Action("ACTION", "ДЕЙСТВИЕ"),
    ShowPayload("Show payload", "Показать данные"),
    HidePayload("Hide payload", "Скрыть данные"),
    PreviousEvent("Previous event", "Предыдущее событие"),
    NextEvent("Next event", "Следующее событие"),
    StateBefore("STATE BEFORE", "СОСТОЯНИЕ ДО"),
    StateAfter("STATE AFTER", "СОСТОЯНИЕ ПОСЛЕ"),
    Resize("Resize debug panel", "Изменить размер панели"),
    Milliseconds("ms", "мс"),
    ;

    fun forLanguage(language: String): String = if (language.substringBefore("-").substringBefore("_").equals("ru", ignoreCase = true)) russian else english
}

@Composable
internal fun historyText(text: HistoryText): String = text.forLanguage(Locale.current.language)
