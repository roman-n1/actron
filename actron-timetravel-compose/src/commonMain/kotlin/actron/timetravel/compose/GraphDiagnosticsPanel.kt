package actron.timetravel.compose

import actron.statechart.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp

@Composable
internal fun GraphDiagnosticsPanel(
    issues: List<GraphDiagnostic>,
    onSelect: (GraphDiagnostic) -> Unit,
) {
    if (issues.isEmpty()) return
    var expanded by remember(issues) { mutableStateOf(false) }
    val russian = Locale.current.language == "ru"
    val errors = issues.count { it.severity == GraphIssueSeverity.Error }
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().padding(8.dp).testTag("graph-diagnostics"),
        color =
            if (errors > 0) {
                colors.errorContainer
            } else if (colors.surface.luminance() > 0.5f) {
                Color(0xFFFFF3CD)
            } else {
                Color(0xFF514315)
            },
        contentColor =
            if (errors > 0) {
                colors.onErrorContainer
            } else if (colors.surface.luminance() > 0.5f) {
                Color(0xFF594300)
            } else {
                Color(0xFFFFE8A3)
            },
        shape = MaterialTheme.shapes.medium,
    ) {
        Column {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().testTag("graph-diagnostics-toggle")) {
                Text(if (russian) "Ошибки: $errors · Предупреждения: ${issues.size - errors} ${if (expanded) "−" else "+"}" else "Errors: $errors · Warnings: ${issues.size - errors} ${if (expanded) "−" else "+"}")
            }
            if (expanded) {
                Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                    issues.forEachIndexed { index, issue ->
                        TextButton(onClick = {
                            onSelect(issue)
                            expanded = false
                        }, modifier = Modifier.fillMaxWidth().testTag("graph-issue-$index")) {
                            Text(issueText(issue.code, russian) + "\n" + (issue.states.map { it.value } + issue.transitions.map { "#${it.index + 1}" }).joinToString(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private fun issueText(
    code: GraphIssueCode,
    ru: Boolean,
): String =
    when (code) {
        GraphIssueCode.DuplicateState -> if (ru) "Повторяется идентификатор состояния" else "Duplicate state identity"
        GraphIssueCode.UnknownInitial -> if (ru) "Начальное состояние не объявлено" else "Initial state is not declared"
        GraphIssueCode.UnknownEndpoint -> if (ru) "Конец перехода ссылается на необъявленное состояние" else "Transition endpoint is not declared"
        GraphIssueCode.InvalidHierarchy -> if (ru) "Некорректная вложенность или начальное дочернее состояние" else "Invalid hierarchy or initial child"
        GraphIssueCode.InvalidHistory -> if (ru) "Некорректное состояние истории" else "Invalid history declaration"
        GraphIssueCode.InvalidTransition -> if (ru) "Недопустимый источник перехода или задержка таймера" else "Invalid transition source or timer delay"
        GraphIssueCode.Unreachable -> if (ru) "Состояние структурно недостижимо" else "State is structurally unreachable"
        GraphIssueCode.NoExit -> if (ru) "Не найден выход из состояния. Возможно, это намеренно." else "No exit found. This may be intentional."
        GraphIssueCode.ShadowedTransition -> if (ru) "Переход перекрыт более ранним безусловным переходом" else "An earlier unconditional transition shadows this transition"
        GraphIssueCode.AnalysisLimit -> if (ru) "Достигнут лимит анализа. Достижимость проверена не полностью." else "Analysis budget reached. Reachability is incomplete."
    }
