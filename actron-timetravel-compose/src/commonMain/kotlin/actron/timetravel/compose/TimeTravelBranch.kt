@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package actron.timetravel.compose

import actron.observability.StoreInstanceId
import actron.timetravel.compose.resources.Res
import actron.timetravel.compose.resources.advance_second
import actron.timetravel.compose.resources.answer
import actron.timetravel.compose.resources.awaiting_command
import actron.timetravel.compose.resources.complete_command
import actron.timetravel.compose.resources.fail_command
import actron.timetravel.compose.resources.offline_experiment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds

@Composable
internal fun PanelBranchBody(
    filter: StoreFilter,
    branch: BranchControls,
    expanded: Boolean,
) {
    val request = branch.request
    if (request is BranchRequest.Refused) PanelProblem(request.reason)
    Text(stringResource(resource = Res.string.offline_experiment), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = { branch.advance(1.seconds) }, modifier = Modifier.testTag("panel-advance")) { Text(stringResource(resource = Res.string.advance_second)) }
    for (store in branch.members.filter { filter.includes(it) }) {
        PanelBranchMember(branch, store, expanded)
    }
    if (expanded) for (decision in branch.decisions.filter { filter.includes(it.store) }) PanelLines(listOf(BranchText.line(decision)))
}

@Composable
internal fun PanelAnswerPicker(
    branch: BranchControls,
    store: StoreInstanceId,
    command: actron.statechart.machine.CommandId,
) {
    var open by remember(branch, store, command) { mutableStateOf(false) }
    val answers = branch.inputsOf(store).filterIsInstance<BranchInput.Answer>()
    if (answers.isNotEmpty()) {
        Box {
            TextButton(onClick = { open = true }, modifier = Modifier.testTag("panel-answers-${store.value}-${command.value}")) { Text(stringResource(resource = Res.string.answer)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (answer in answers) {
                    DropdownMenuItem(
                        text = { Text(answer.label) },
                        onClick = {
                            open = false
                            branch.answer(answer, command)
                        },
                        modifier = Modifier.testTag("panel-answer-${store.value}-${command.value}-${answer.label}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelBranchMember(
    branch: BranchControls,
    store: StoreInstanceId,
    expanded: Boolean,
) {
    PanelSnapshot(store, branch.snapshot(store))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (input in branch.inputsOf(store)) {
            when (input) {
                is BranchInput.Dispatch -> TextButton(onClick = { branch.dispatch(input) }, modifier = Modifier.testTag("panel-input-${store.value}-${input.label}")) { Text(input.label) }
                is BranchInput.Feed -> TextButton(onClick = { branch.feed(input) }, modifier = Modifier.testTag("panel-input-${store.value}-${input.label}")) { Text(input.label) }
                is BranchInput.Answer -> Unit
            }
        }
    }
    for (command in branch.awaiting(store)) {
        Text(stringResource(resource = Res.string.awaiting_command, command.command), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PanelAnswerPicker(branch, store, command.id)
            TextButton(onClick = { branch.complete(store, command.id) }, modifier = Modifier.testTag("panel-complete-${store.value}-${command.id.value}")) { Text(stringResource(resource = Res.string.complete_command)) }
            TextButton(onClick = { branch.fail(store, command.id) }, modifier = Modifier.testTag("panel-fail-${store.value}-${command.id.value}")) { Text(stringResource(resource = Res.string.fail_command)) }
        }
    }
    if (expanded) PanelLines(listOf("Clock: ${branch.now(store).sinceStart}", "Queued: ${branch.queued(store)}"))
}
