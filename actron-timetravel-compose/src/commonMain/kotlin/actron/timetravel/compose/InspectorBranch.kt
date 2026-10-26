package actron.timetravel.compose

import actron.timetravel.compose.resources.Res
import actron.timetravel.compose.resources.advance_second_short
import actron.timetravel.compose.resources.branch_decisions
import actron.timetravel.compose.resources.branch_heading
import actron.timetravel.compose.resources.definition_of
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InspectorBranch(
    branch: BranchControls,
    modifier: Modifier,
) {
    Column(modifier.padding(8.dp).verticalScroll(rememberScrollState()).testTag("branch")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InspectorHeading(stringResource(resource = Res.string.branch_heading))
            OutlinedButton(onClick = { branch.advance(1.seconds) }, modifier = Modifier.testTag("branch-advance")) { Text(stringResource(resource = Res.string.advance_second_short)) }
        }
        val request = branch.request
        if (request is BranchRequest.Refused) Text("! ${request.reason}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828), modifier = Modifier.testTag("branch-problem"))
        for (store in branch.members) InspectorBranchMember(inspectorBranchMember(branch, store))
        InspectorHeading(stringResource(resource = Res.string.branch_decisions, branch.decisions.size))
        for (decision in branch.decisions) Text(BranchText.line(decision), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("branch-decision-${decision.index}"))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InspectorBranchMember(model: InspectorBranchMemberPresentation) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.semantics(mergeDescendants = true) {}.testTag("branch-${model.store.value}")) {
            Text(model.heading.resolve(), fontWeight = FontWeight.Bold)
            Text(model.context.resolve(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            for (line in model.queued) Text(line.resolve(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (action in model.inputs) InspectorBranchAction(action) }
        for (command in model.commands) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag(command.tag)) {
                Text(command.label.resolve(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                for (action in command.actions) InspectorBranchAction(action)
            }
        }
        DefinitionPanel(model.definition, stringResource(resource = Res.string.definition_of, model.store.value), tag = "branch-definition-${model.store.value}")
    }
}

@Composable
private fun InspectorBranchAction(action: InspectorActionPresentation) {
    OutlinedButton(onClick = action.invoke, modifier = Modifier.testTag(action.tag)) { Text(action.label.resolve()) }
}
