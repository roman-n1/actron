package actron.timetravel.compose

import actron.statechart.GraphDiagnostic
import actron.statechart.GraphIssueCode
import actron.statechart.GraphIssueSeverity
import actron.statechart.Transition
import actron.timetravel.compose.resources.Res
import actron.timetravel.compose.resources.graph_diagnostic_counts
import actron.timetravel.compose.resources.graph_issue_analysis_limit
import actron.timetravel.compose.resources.graph_issue_duplicate_state
import actron.timetravel.compose.resources.graph_issue_invalid_hierarchy
import actron.timetravel.compose.resources.graph_issue_invalid_history
import actron.timetravel.compose.resources.graph_issue_invalid_transition
import actron.timetravel.compose.resources.graph_issue_no_exit
import actron.timetravel.compose.resources.graph_issue_shadowed_transition
import actron.timetravel.compose.resources.graph_issue_unknown_endpoint
import actron.timetravel.compose.resources.graph_issue_unknown_initial
import actron.timetravel.compose.resources.graph_issue_unreachable
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
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun GraphDiagnosticsPanel(
    issues: List<GraphDiagnostic>,
    onSelect: (GraphDiagnostic) -> Unit,
) {
    if (issues.isEmpty()) return
    val expanded = remember(issues) { mutableStateOf(false) }
    val errors = graphErrorCount(issues = issues)
    val heading = stringResource(resource = Res.string.graph_diagnostic_counts, errors, issues.size - errors, if (expanded.value) "−" else "+")
    val labels = GraphIssueCode.entries.associateWith { stringResource(resource = issueResource(code = it)) }
    val model = graphDiagnosticPresentation(issues = issues, heading = heading, labels = labels, colors = MaterialTheme.colorScheme)
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

private fun issueResource(code: GraphIssueCode): org.jetbrains.compose.resources.StringResource =
    when (code) {
        GraphIssueCode.DuplicateState -> Res.string.graph_issue_duplicate_state
        GraphIssueCode.UnknownInitial -> Res.string.graph_issue_unknown_initial
        GraphIssueCode.UnknownEndpoint -> Res.string.graph_issue_unknown_endpoint
        GraphIssueCode.InvalidHierarchy -> Res.string.graph_issue_invalid_hierarchy
        GraphIssueCode.InvalidHistory -> Res.string.graph_issue_invalid_history
        GraphIssueCode.InvalidTransition -> Res.string.graph_issue_invalid_transition
        GraphIssueCode.Unreachable -> Res.string.graph_issue_unreachable
        GraphIssueCode.NoExit -> Res.string.graph_issue_no_exit
        GraphIssueCode.ShadowedTransition -> Res.string.graph_issue_shadowed_transition
        GraphIssueCode.AnalysisLimit -> Res.string.graph_issue_analysis_limit
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
    heading: String,
    labels: Map<GraphIssueCode, String>,
    colors: androidx.compose.material3.ColorScheme,
): GraphDiagnosticPresentation {
    val errors = issues.count { it.severity == GraphIssueSeverity.Error }
    return GraphDiagnosticPresentation(
        heading,
        when {
            errors > 0 -> colors.errorContainer
            else -> colors.tertiaryContainer
        },
        when {
            errors > 0 -> colors.onErrorContainer
            else -> colors.onTertiaryContainer
        },
        issues.map { issue -> GraphDiagnosticRow(issue, labels.getValue(key = issue.code) + "\n" + (issue.states.map { it.value } + issue.transitions.map { "#${it.index + 1}" }).joinToString()) },
    )
}

private fun graphErrorCount(issues: List<GraphDiagnostic>): Int = issues.count { it.severity == GraphIssueSeverity.Error }
