@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.timetravel.compose

import actron.observability.StoreInstanceId
import actron.statechart.machine.LaneId

internal data class InspectorActionPresentation(
    val label: String,
    val tag: String,
    val invoke: () -> Unit,
)

internal data class InspectorCommandPresentation(
    val tag: String,
    val label: String,
    val actions: List<InspectorActionPresentation>,
)

internal data class InspectorBranchMemberPresentation(
    val store: StoreInstanceId,
    val heading: String,
    val context: String,
    val queued: List<String>,
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
                    is BranchInput.Dispatch -> add(InspectorActionPresentation(input.label, "branch-input-${store.value}-${input.label}") { branch.dispatch(input) })
                    is BranchInput.Feed -> add(InspectorActionPresentation("${input.label} (${input.source.value})", "branch-input-${store.value}-${input.label}") { branch.feed(input) })
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
                    is LaneId -> " in ${value.value}"
                    else -> ""
                }
            InspectorCommandPresentation(
                "branch-awaiting-${store.value}-${id.value}",
                "awaiting c${id.value} ${registration.command}$lane",
                listOf(InspectorActionPresentation("complete", "branch-complete-${store.value}-${id.value}") { branch.complete(store, id) }, InspectorActionPresentation("fail", "branch-fail-${store.value}-${id.value}") { branch.fail(store, id) }) +
                    answers.map { input -> InspectorActionPresentation(input.label, "branch-answer-${store.value}-${id.value}-${input.label}") { branch.answer(input, id) } },
            )
        }
    return InspectorBranchMemberPresentation(
        store,
        "${store.value}  revision ${snapshot.revision}  now ${branch.now(store).sinceStart}",
        "active ${snapshot.configuration.active.map { it.value }.sorted().joinToString(",", "[", "]")}  context ${snapshot.context}",
        branch.queued(store).map { (lane, registrations) -> "queued in ${lane.value}: ${registrations.joinToString(",") { "c${it.id.value}" }}" },
        actions,
        commands,
        branch.mermaid(store),
    )
}
