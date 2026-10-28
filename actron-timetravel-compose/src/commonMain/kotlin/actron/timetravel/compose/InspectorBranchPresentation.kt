package actron.timetravel.compose

import actron.observability.StoreInstanceId
import actron.statechart.machine.LaneId
import actron.timetravel.compose.resources.Res
import actron.timetravel.compose.resources.branch_active_context
import actron.timetravel.compose.resources.branch_awaiting
import actron.timetravel.compose.resources.branch_lane
import actron.timetravel.compose.resources.branch_queued
import actron.timetravel.compose.resources.branch_revision_time
import actron.timetravel.compose.resources.complete_command
import actron.timetravel.compose.resources.fail_command
import actron.timetravel.compose.resources.verbatim

internal data class InspectorActionPresentation(
    val label: PanelText,
    val tag: String,
    val invoke: () -> Unit,
)

internal data class InspectorCommandPresentation(
    val tag: String,
    val label: PanelText,
    val actions: List<InspectorActionPresentation>,
)

internal data class InspectorBranchMemberPresentation(
    val store: StoreInstanceId,
    val heading: PanelText,
    val context: PanelText,
    val queued: List<PanelText>,
    val inputs: List<InspectorActionPresentation>,
    val commands: List<InspectorCommandPresentation>,
    val definition: String,
)

internal fun inspectorBranchMember(
    branch: BranchControls,
    store: StoreInstanceId,
): InspectorBranchMemberPresentation {
    val snapshot = branch.snapshot(store)
    val inputs = branch.inputsOf(store)
    val actions =
        buildList {
            for (input in inputs) {
                when (input) {
                    is BranchInput.Dispatch -> add(InspectorActionPresentation(panelText(resource = Res.string.verbatim, input.label), "branch-input-${store.value}-${input.label}") { branch.dispatch(input) })
                    is BranchInput.Feed -> add(InspectorActionPresentation(panelText(resource = Res.string.verbatim, "${input.label} (${input.source.value})"), "branch-input-${store.value}-${input.label}") { branch.feed(input) })
                    is BranchInput.Answer -> Unit
                }
            }
        }
    val answers = inputs.filterIsInstance<BranchInput.Answer>()
    val commands =
        branch.awaiting(store).map { registration ->
            val id = registration.id
            val lane =
                when (val value = registration.lane) {
                    is LaneId -> panelText(resource = Res.string.branch_lane, value.value)
                    else -> panelText(resource = Res.string.verbatim, "")
                }
            InspectorCommandPresentation(
                "branch-awaiting-${store.value}-${id.value}",
                panelText(resource = Res.string.branch_awaiting, id.value, registration.command, lane),
                listOf(InspectorActionPresentation(panelText(resource = Res.string.complete_command), "branch-complete-${store.value}-${id.value}") { branch.complete(store, id) }, InspectorActionPresentation(panelText(resource = Res.string.fail_command), "branch-fail-${store.value}-${id.value}") { branch.fail(store, id) }) +
                    answers.map { input -> InspectorActionPresentation(panelText(resource = Res.string.verbatim, input.label), "branch-answer-${store.value}-${id.value}-${input.label}") { branch.answer(input, id) } },
            )
        }
    return InspectorBranchMemberPresentation(
        store,
        panelText(resource = Res.string.branch_revision_time, store.value, snapshot.revision, branch.now(store).sinceStart),
        panelText(
            resource = Res.string.branch_active_context,
            snapshot.configuration.active
                .map { it.value }
                .sorted()
                .joinToString(separator = ",", prefix = "[", postfix = "]"),
            snapshot.context,
        ),
        branch.queued(store).map { (lane, registrations) -> panelText(resource = Res.string.branch_queued, lane.value, registrations.joinToString(separator = ",") { "c${it.id.value}" }) },
        actions,
        commands,
        branch.mermaid(store),
    )
}
