@file:OptIn(actron.core.ExperimentalActronApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package actron.timetravel.compose

import actron.core.ExperimentalActronApi
import actron.observability.StoreInstanceId
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineSnapshot
import actron.timetravel.RecordedStep
import actron.timetravel.inspect.Availability
import actron.timetravel.inspect.InspectorText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.seconds

/**
 * A compact, embeddable inspector with a machine picker, input history and expandable details.
 * It uses the host's [MaterialTheme] and [modifier]; it owns no window, sheet, height or app UI.
 * Place it in a bottom dock, sheet or any other Compose container. A bounded height keeps the
 * controls above a scrollable body; an unbounded host gets content-sized layout.
 *
 * This overload observes [state]'s journal (including live updates from
 * [rememberLiveInspector]). Recording must be installed independently of panel visibility.
 * Journal selection is inspection only; use a replay overload to re-decide recorded inputs.
 */
@ExperimentalActronApi
@Composable
fun TimeTravelPanel(
    state: InspectorState,
    modifier: Modifier = Modifier,
) {
    PanelLayout(state, state.mode, state.inspector.stores.map { it.id }, modifier) { expanded, vertical ->
        val visible = state.visibleTimeline
        if (visible.isEmpty()) PanelEmpty()
        val selection = state.selection
        PanelTimeline(
            visible.map { it.index },
            if (selection is InspectorSelection.Position) selection.index else -1,
            vertical,
            label = { InspectorText.line(state.inspector.timeline[it]) },
            onSelect = state::select,
        )
        if (selection is InspectorSelection.Position && selection.index in state.inspector.timeline.indices) {
            val item = state.inspector.timeline[selection.index]
            Text(InspectorText.line(item), style = MaterialTheme.typography.bodySmall)
            if (expanded) PanelLines(InspectorText.detail(item))
        }
        if (expanded) {
            for (store in state.inspector.stores.filter { state.filter.includes(it.id) }) {
                Text("${store.id.value}: ${InspectorText.completeness(store.completeness)}", style = MaterialTheme.typography.bodySmall)
                for (reason in store.completeness.reasons) PanelProblem(InspectorText.reason(reason))
            }
        }
    }
}

/** Replays one machine offline. [ReplayControls.snapshot] can separately drive an app preview. */
@ExperimentalActronApi
@Composable
fun TimeTravelPanel(
    state: InspectorState,
    replay: ReplayControls<*, *, *, *>,
    modifier: Modifier = Modifier,
) {
    PanelLayout(state, InspectorMode.Replay, listOf(replay.store), modifier, navigation = {
        PanelNavigation(replay.position, replay.length, replay.canStepBackward, replay.forwardAvailability is Availability.Available, replay::stepBackward, replay::stepForward, replay::verify)
    }) { expanded, vertical ->
        val movement = replay.movement
        if (movement is ReplayMovement.Diverged) PanelProblem(movement.description)
        val verification = replay.verification
        if (verification is ReplayVerification.Checked) PanelVerdict(verification.verdict)
        if (replay.length == 0) PanelEmpty()
        val selected = if (movement is ReplayMovement.Diverged) movement.position else replay.position - 1
        PanelTimeline(
            replay.session.recording.steps.indices
                .toList(),
            selected,
            vertical,
            label = {
                replay.session.recording.steps[it]
                    .input
                    .panelLabel()
            },
            onSelect = { replay.seek(it + 1) },
        )
        Text("${replay.store.value} · ${replay.snapshot.configuration.active.map { it.value }.sorted().joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
        if (expanded) {
            PanelSnapshot(replay.store, replay.snapshot)
            val index = if (movement is ReplayMovement.Diverged) movement.position else replay.position - 1
            if (index in replay.session.recording.steps.indices) PanelRecorded(replay.session.recording.steps[index], replay.session.recording.snapshotAt(index), replay.session.recording.snapshotAt(index + 1))
            TextButton(onClick = replay::explainNext) { Text("Explain next input / check invariants") }
            StateChartPanel(replay.session.machine.chart, replay.snapshot, inspection = replay.inspection)
        }
    }
}

/** One global cursor for the group. Choosing a machine filters rows without moving any member. */
@ExperimentalActronApi
@Composable
fun TimeTravelPanel(
    state: InspectorState,
    groupReplay: GroupReplayControls,
    modifier: Modifier = Modifier,
) {
    PanelLayout(state, InspectorMode.Replay, groupReplay.members, modifier, navigation = { PanelGroupNavigation(groupReplay) }) { expanded, vertical ->
        PanelGroupBody(state.filter, groupReplay, expanded, vertical)
    }
}

/** The group's replay and isolated experiments, using the application's scripted branch inputs. */
@ExperimentalActronApi
@Composable
fun TimeTravelPanel(
    state: InspectorState,
    timeTravel: GroupTimeTravelControls,
    modifier: Modifier = Modifier,
) {
    val travel = timeTravel.travel
    PanelLayout(state, timeTravel.mode, timeTravel.replay.members, modifier, navigation = {
        if (travel is GroupTravel.Experiment) {
            TextButton(onClick = timeTravel::returnToReplay, modifier = Modifier.testTag("panel-return")) { Text("Return to recording") }
            Text("From step ${travel.position}", style = MaterialTheme.typography.labelMedium)
        } else {
            PanelGroupNavigation(timeTravel.replay)
            TextButton(onClick = timeTravel::branchHere, modifier = Modifier.testTag("panel-branch")) { Text("Experiment here") }
        }
    }) { expanded, vertical ->
        if (travel is GroupTravel.Experiment) {
            PanelBranchBody(state.filter, travel.branch, expanded)
        } else {
            PanelGroupBody(state.filter, timeTravel.replay, expanded, vertical)
        }
    }
}

@Composable
private fun PanelLayout(
    state: InspectorState,
    mode: InspectorMode,
    stores: List<StoreInstanceId>,
    modifier: Modifier,
    navigation: @Composable () -> Unit = {},
    content: @Composable (Boolean, Boolean) -> Unit,
) {
    var expanded by remember(state) { mutableStateOf(false) }
    Surface(modifier = modifier.fillMaxWidth().testTag("time-travel-panel"), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints {
            val bounded = constraints.hasBoundedHeight
            val vertical = maxWidth < 400.dp && maxHeight >= 400.dp
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    PanelMachinePicker(state, stores)
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(vertical = 10.dp)) {
                        Text(
                            when (mode) {
                                InspectorMode.Live -> "Live"
                                InspectorMode.Inspect -> "History"
                                InspectorMode.Replay -> "Replay"
                                InspectorMode.Branch -> "Experiment"
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp).testTag("panel-mode"),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("panel-details-toggle")) { Text(if (expanded) "Hide details" else "Details") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) { navigation() }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val body = if (bounded) Modifier.weight(1f, fill = false) else Modifier
                Column(modifier = body.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!state.inspector.completeness.isComplete) {
                        PanelProblem(
                            "Partial history: " +
                                state.inspector.completeness.reasons
                                    .joinToString("; ") { InspectorText.reason(it) },
                        )
                    }
                    content(expanded, vertical)
                }
            }
        }
    }
}

@Composable
private fun PanelMachinePicker(
    state: InspectorState,
    stores: List<StoreInstanceId>,
) {
    var open by remember(state) { mutableStateOf(false) }
    val filter = state.filter
    Box {
        TextButton(onClick = { open = true }, enabled = stores.isNotEmpty(), modifier = Modifier.testTag("panel-machines")) {
            Text(if (filter is StoreFilter.Single) filter.store.value else "All machines (${stores.size})", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("All machines") }, onClick = {
                state.filter = StoreFilter.All
                open = false
            }, modifier = Modifier.testTag("panel-machine-all"))
            for (store in stores) {
                DropdownMenuItem(text = { Text(store.value) }, onClick = {
                    state.filter = StoreFilter.Single(store)
                    open = false
                }, modifier = Modifier.testTag("panel-machine-${store.value}"))
            }
        }
    }
}

@Composable
private fun PanelNavigation(
    position: Int,
    length: Int,
    back: Boolean,
    forward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onVerify: () -> Unit,
) {
    TextButton(onClick = onBack, enabled = back, modifier = Modifier.testTag("panel-back")) { Text("Back") }
    Text("$position / $length", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 14.dp).testTag("panel-position"))
    TextButton(onClick = onForward, enabled = forward, modifier = Modifier.testTag("panel-forward")) { Text("Next") }
    TextButton(onClick = onVerify, modifier = Modifier.testTag("panel-verify")) { Text("Verify") }
}

@Composable
private fun PanelGroupNavigation(replay: GroupReplayControls) = PanelNavigation(replay.position, replay.length, replay.canStepBackward, replay.canStepForward, replay::stepBackward, replay::stepForward, replay::verify)

@Composable
private fun PanelGroupBody(
    filter: StoreFilter,
    replay: GroupReplayControls,
    expanded: Boolean,
    vertical: Boolean,
) {
    val movement = replay.movement
    if (movement is GroupReplayMovement.Diverged) PanelProblem(movement.mismatch.explain())
    val verification = replay.verification
    if (verification is GroupVerification.Checked) {
        if (verification.problems.isEmpty()) {
            PanelVerdict("Verified ${replay.length} inputs · no divergence")
        } else {
            for (problem in verification.problems) PanelProblem(problem.explain())
        }
    }
    val visible =
        replay.session.recording.order
            .withIndex()
            .filter { filter.includes(it.value.store) }
    val selected = if (replay.hasSelection) replay.selectedPosition().index else -1
    if (visible.isEmpty()) PanelEmpty()
    PanelTimeline(visible.map { it.index }, selected, vertical, label = { index ->
        val step = replay.at(index)
        "${step.store.value} · ${step.recorded.input.panelLabel()}"
    }, onSelect = { replay.seek(it + 1) })
    if (replay.hasSelection) {
        val item = replay.selectedPosition()
        Text("${item.store.value}: ${item.before.configuration.active.names()} → ${item.after.configuration.active.names()}", style = MaterialTheme.typography.bodySmall)
        if (expanded) PanelRecorded(item.recorded, item.before, item.after)
    } else {
        Text("Before the first input", style = MaterialTheme.typography.bodySmall)
    }
    if (expanded) {
        for (store in replay.members.filter { filter.includes(it) }) {
            PanelSnapshot(store, replay.snapshot(store))
            val checkpoint = replay.checkpoint(store)
            PanelLines(listOf("Clock: ${checkpoint.now.sinceStart}", "Running: ${checkpoint.lanes.running.keys}", "Queued: ${checkpoint.lanes.queued}", "Ending: ${checkpoint.ending.keys}", "Pending effects: ${checkpoint.effects}"))
        }
    }
}

/** Bottom docks get a horizontal history; tall, narrow sidebars get a bounded vertical list. */
@Composable
private fun PanelTimeline(
    indices: List<Int>,
    selected: Int,
    vertical: Boolean,
    label: (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    val timeline = rememberLazyListState()
    LaunchedEffect(selected, indices) {
        val target = indices.indexOfLast { it <= selected }
        if (target >= 0) timeline.scrollToItem(target)
    }
    if (vertical) {
        LazyColumn(state = timeline, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).testTag("panel-history-vertical")) {
            items(indices, key = { it }) { index -> PanelStep(index, label(index), index == selected, Modifier.fillMaxWidth()) { onSelect(index) } }
        }
    } else {
        LazyRow(state = timeline, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().testTag("panel-history-horizontal")) {
            items(indices, key = { it }) { index -> PanelStep(index, label(index), index == selected) { onSelect(index) } }
        }
    }
}

@Composable
private fun PanelStep(
    index: Int,
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier =
            modifier
                .widthIn(max = 220.dp)
                .semantics { selected = isSelected }
                .clickable(onClick = onClick)
                .testTag("panel-step-$index"),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("#$index", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PanelRecorded(
    step: RecordedStep<*, *, *, *>,
    before: MachineSnapshot<*>,
    after: MachineSnapshot<*>,
) {
    PanelLines(listOf("Input: ${step.input}", "Recorded before: ${before.context}", "Recorded after: ${after.context}"))
    when (step) {
        is RecordedStep.Committed<*, *, *, *> -> PanelLines(listOf("Transitions: ${step.decision.transitions}", "Commands: ${step.decision.commands}", "Timers: ${step.decision.timersScheduled}", "Events: ${step.decision.effects}"))
        is RecordedStep.Ignored<*, *, *, *> -> Text("Ignored: ${step.reason}")
        is RecordedStep.Failed<*, *, *, *> -> PanelProblem("Failed: ${step.failure}")
    }
}

@Composable
private fun PanelSnapshot(
    store: StoreInstanceId,
    snapshot: MachineSnapshot<*>,
) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("panel-selected-snapshot")) {
        Text("${store.value} · revision ${snapshot.revision}", style = MaterialTheme.typography.titleSmall)
        Text("${snapshot.configuration.active.names()} · ${snapshot.context}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Text("Commands: ${snapshot.commands.keys} · timers: ${snapshot.timers.keys}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PanelBranchBody(
    filter: StoreFilter,
    branch: BranchControls,
    expanded: Boolean,
) {
    val request = branch.request
    if (request is BranchRequest.Refused) PanelProblem(request.reason)
    Text("Offline experiment · recording unchanged", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = { branch.advance(1.seconds) }, modifier = Modifier.testTag("panel-advance")) { Text("Advance 1 second") }
    for (store in branch.members.filter { filter.includes(it) }) {
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
            Text("Awaiting ${command.command}", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PanelAnswerPicker(branch, store, command.id)
                TextButton(onClick = { branch.complete(store, command.id) }, modifier = Modifier.testTag("panel-complete-${store.value}-${command.id.value}")) { Text("Complete") }
                TextButton(onClick = { branch.fail(store, command.id) }, modifier = Modifier.testTag("panel-fail-${store.value}-${command.id.value}")) { Text("Fail") }
            }
        }
        if (expanded) PanelLines(listOf("Clock: ${branch.now(store).sinceStart}", "Queued: ${branch.queued(store)}"))
    }
    if (expanded) for (decision in branch.decisions.filter { filter.includes(it.store) }) PanelLines(listOf(BranchText.line(decision)))
}

@Composable
private fun PanelAnswerPicker(
    branch: BranchControls,
    store: StoreInstanceId,
    command: actron.statechart.machine.CommandId,
) {
    var open by remember(branch, store, command) { mutableStateOf(false) }
    val answers = branch.inputsOf(store).filterIsInstance<BranchInput.Answer>()
    if (answers.isNotEmpty()) {
        Box {
            TextButton(onClick = { open = true }, modifier = Modifier.testTag("panel-answers-${store.value}-${command.value}")) { Text("Answer…") }
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
private fun PanelProblem(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("panel-problem"))
}

@Composable
private fun PanelVerdict(message: String) = Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("panel-verdict"))

@Composable
private fun PanelEmpty() = Text("No recorded inputs for this machine", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("panel-empty"))

@Composable
private fun PanelLines(lines: List<String>) {
    SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { for (line in lines) Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) } }
}

private fun Set<actron.statechart.StateId>.names(): String = map { it.value }.sorted().joinToString(", ")

private fun MachineInput<*>.panelLabel(): String =
    when (this) {
        is MachineInput.Start -> "Start"
        is MachineInput.Dispatch -> action::class.simpleName ?: "Action"
        is MachineInput.CommandResult -> "Answer · ${action::class.simpleName ?: "Result"}"
        is MachineInput.CommandCompleted -> "Command completed"
        is MachineInput.CommandFailed -> "Command failed"
        is MachineInput.CommandAbandoned -> "Command abandoned"
        is MachineInput.TimerFired -> "Timer fired"
        is MachineInput.BridgeReceived -> "Bridge · ${action::class.simpleName ?: "Message"}"
        is MachineInput.External -> "Source · ${action::class.simpleName ?: "Data"}"
    }
