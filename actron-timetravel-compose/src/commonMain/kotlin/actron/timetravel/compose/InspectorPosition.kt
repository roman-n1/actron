@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.timetravel.compose

import actron.core.ExperimentalActronApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
internal fun InspectorPosition(
    state: InspectorState,
    modifier: Modifier,
    extra: @Composable () -> Unit,
) {
    val model = inspectorPosition(state)
    Column(modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
        Column(Modifier.semantics(mergeDescendants = true) {}.testTag("position")) {
            InspectorHeading(model.heading)
            when (val content = model.content) {
                is InspectorPositionContent.Prompt -> {
                    Text(content.text, style = MaterialTheme.typography.bodySmall)
                }

                is InspectorPositionContent.Journal -> {
                    for (line in content.lines) Text(line.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, fontWeight = line.weight)
                    for (notice in content.notices) {
                        Spacer(Modifier.height(8.dp))
                        Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
        extra()
    }
}

@Composable
internal fun InspectorReplayPosition(replay: ReplayControls<*, *, *, *>) {
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { replay.explainNext() }, modifier = Modifier.testTag("explain-next")) { Text("Explain next input / check invariants") }
    val inspection = replay.inspection
    if (inspection is ChartInspection.Decided) Text(inspection.outcome)
    StateChartPanel(
        replay.session.machine.chart,
        replay.snapshot,
        previousActive =
            replay.session.recording
                .snapshotAt((replay.position - 1).coerceAtLeast(0))
                .configuration.active,
        inspection = inspection,
    )
    DefinitionPanel(replay.mermaid(), "Definition of ${replay.store} at ${replay.position}", tag = "definition")
}

@ExperimentalActronApi
@Composable
internal fun InspectorDefinition(
    mermaid: String,
    title: String,
    tag: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.semantics(mergeDescendants = true) {}.testTag(tag)) {
        InspectorHeading(title)
        SelectionContainer {
            Text(mermaid, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}
