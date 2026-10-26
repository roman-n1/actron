package actron.timetravel.compose

import actron.statechart.GraphDiagnostic
import actron.statechart.GraphIssueCode
import actron.statechart.GraphIssueSeverity
import actron.statechart.Transition
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp

@Composable
internal fun GraphDiagnosticsPanel(
    issues: List<GraphDiagnostic>,
    onSelect: (GraphDiagnostic) -> Unit,
) {
    if (issues.isEmpty()) return
    val expanded = remember(issues) { mutableStateOf(false) }
    val model = graphDiagnosticPresentation(issues, Locale.current.language, MaterialTheme.colorScheme, expanded.value)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(8.dp).testTag("graph-diagnostics"),
        color = model.background,
        contentColor = model.foreground,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column {
            TextButton(onClick = { expanded.toggle() }, colors = ButtonDefaults.textButtonColors(contentColor = model.foreground), modifier = Modifier.fillMaxWidth().testTag("graph-diagnostics-toggle")) {
                Text(model.heading)
            }
            if (expanded.value) {
                Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                    model.rows.forEachIndexed { index, row ->
                        TextButton(onClick = {
                            onSelect(row.issue)
                            expanded.value = false
                        }, colors = ButtonDefaults.textButtonColors(contentColor = model.foreground), modifier = Modifier.fillMaxWidth().testTag("graph-issue-$index")) {
                            Text(row.text, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
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
): String {
    val labels = issueLabels(code)
    return if (ru) labels.first else labels.second
}

private fun issueLabels(code: GraphIssueCode): Pair<String, String> =
    when (code) {
        GraphIssueCode.DuplicateState -> "Повторяется идентификатор состояния" to "Duplicate state identity"
        GraphIssueCode.UnknownInitial -> "Начальное состояние не объявлено" to "Initial state is not declared"
        GraphIssueCode.UnknownEndpoint -> "Конец перехода ссылается на необъявленное состояние" to "Transition endpoint is not declared"
        GraphIssueCode.InvalidHierarchy -> "Некорректная вложенность или начальное дочернее состояние" to "Invalid hierarchy or initial child"
        GraphIssueCode.InvalidHistory -> "Некорректное состояние истории" to "Invalid history declaration"
        GraphIssueCode.InvalidTransition -> "Недопустимый источник перехода или задержка таймера" to "Invalid transition source or timer delay"
        GraphIssueCode.Unreachable -> "Состояние структурно недостижимо" to "State is structurally unreachable"
        GraphIssueCode.NoExit -> "Не найден выход из состояния. Возможно, это намеренно." to "No exit found. This may be intentional."
        GraphIssueCode.ShadowedTransition -> "Переход перекрыт более ранним безусловным переходом" to "An earlier unconditional transition shadows this transition"
        GraphIssueCode.AnalysisLimit -> "Достигнут лимит анализа. Достижимость проверена не полностью." to "Analysis budget reached. Reachability is incomplete."
    }

private data class GraphDiagnosticRow(
    val issue: GraphDiagnostic,
    val text: String,
)

private data class GraphDiagnosticPresentation(
    val heading: String,
    val background: Color,
    val foreground: Color,
    val rows: List<GraphDiagnosticRow>,
)

private fun graphDiagnosticPresentation(
    issues: List<GraphDiagnostic>,
    language: String,
    colors: androidx.compose.material3.ColorScheme,
    expanded: Boolean,
): GraphDiagnosticPresentation {
    val russian = language == "ru"
    val errors = issues.count { it.severity == GraphIssueSeverity.Error }
    val sign = if (expanded) "−" else "+"
    return GraphDiagnosticPresentation(
        if (russian) "Ошибки: $errors · Предупреждения: ${issues.size - errors} $sign" else "Errors: $errors · Warnings: ${issues.size - errors} $sign",
        when {
            errors > 0 -> colors.errorContainer
            else -> colors.tertiaryContainer
        },
        when {
            errors > 0 -> colors.onErrorContainer
            else -> colors.onTertiaryContainer
        },
        issues.map { issue -> GraphDiagnosticRow(issue, issueText(issue.code, russian) + "\n" + (issue.states.map { it.value } + issue.transitions.map { "#${it.index + 1}" }).joinToString()) },
    )
}
