package actron.statechart

import actron.statechart.machine.TransitionId

/** Static diagnostics never run action matchers, guards, reducers or IO. */
enum class GraphIssueSeverity { Error, Warning }

enum class GraphIssueCode {
    DuplicateState,
    UnknownInitial,
    UnknownEndpoint,
    InvalidHierarchy,
    InvalidHistory,
    InvalidTransition,
    Unreachable,
    NoExit,
    ShadowedTransition,
    AnalysisLimit,
}

data class GraphDiagnostic(
    val code: GraphIssueCode,
    val severity: GraphIssueSeverity,
    val states: Set<StateId> = emptySet(),
    val transitions: Set<TransitionId> = emptySet(),
)

/**
 * Bounded, conservative structural analysis. Opaque guards are assumed possible.
 * Warnings describe potential problems, not proof of broken application behavior.
 */
fun StateChartDefinition.diagnose(
    maxConfigurations: Int = 1_000,
    maxEdges: Int = 10_000,
): List<GraphDiagnostic> {
    require(maxConfigurations > 0 && maxEdges > 0)
    val issues = mutableListOf<GraphDiagnostic>()

    fun error(
        code: GraphIssueCode,
        ids: Set<StateId>,
        edges: Set<TransitionId> = emptySet(),
    ) {
        issues += GraphDiagnostic(code, GraphIssueSeverity.Error, ids, edges)
    }
    for (issue in endpointIssues() + hierarchyIssues() + historyIssues()) {
        when (issue) {
            is ValidationIssue.DuplicateStateId -> error(GraphIssueCode.DuplicateState, setOf(issue.id))
            is ValidationIssue.UnknownInitialState -> error(GraphIssueCode.UnknownInitial, setOf(issue.id))
            is ValidationIssue.UnknownTransitionSource -> error(GraphIssueCode.UnknownEndpoint, setOf(issue.transition.source, issue.transition.target), transitions.indices.filter { transitions[it] == issue.transition }.mapTo(linkedSetOf(), ::TransitionId))
            is ValidationIssue.UnknownTransitionTarget -> error(GraphIssueCode.UnknownEndpoint, setOf(issue.transition.source, issue.transition.target), transitions.indices.filter { transitions[it] == issue.transition }.mapTo(linkedSetOf(), ::TransitionId))
            is ValidationIssue.UnknownParent -> error(GraphIssueCode.InvalidHierarchy, setOf(issue.id, issue.parent))
            is ValidationIssue.AtomicParent -> error(GraphIssueCode.InvalidHierarchy, setOf(issue.id, issue.parent))
            is ValidationIssue.HistoryParent -> error(GraphIssueCode.InvalidHierarchy, setOf(issue.id, issue.parent))
            is ValidationIssue.ParentCycle -> error(GraphIssueCode.InvalidHierarchy, issue.states.toSet())
            is ValidationIssue.InitialNotChild -> error(GraphIssueCode.InvalidHierarchy, setOf(issue.id, issue.initial))
            is ValidationIssue.EmptyCompoundState -> error(GraphIssueCode.InvalidHierarchy, setOf(issue.id))
            is ValidationIssue.HistoryAsInitial -> error(GraphIssueCode.InvalidHistory, setOf(issue.initial))
            is ValidationIssue.InvalidHistoryDefault -> error(GraphIssueCode.InvalidHistory, setOf(issue.id, issue.default))
            else -> Unit
        }
    }
    for (node in states) {
        if (node is ParallelState && childrenOf(node.id).none { it !is HistoryState }) error(GraphIssueCode.InvalidHierarchy, setOf(node.id))
    }
    transitions.forEachIndexed { index, transition ->
        if (nodeSatisfies(transition.source) { it is FinalState || it is HistoryState } ||
            (transition.trigger == Trigger.Completion && !nodeSatisfies(transition.source) { it is CompoundState || it is ParallelState }) ||
            (transition.trigger is Trigger.After && !transition.trigger.delay.isPositive())
        ) {
            error(GraphIssueCode.InvalidTransition, setOf(transition.source), setOf(TransitionId(index)))
        }
    }
    if (issues.isNotEmpty()) return issues.distinct()
    val analysis = analyzeReachability(maxConfigurations, maxEdges)
    if (analysis.truncated) issues += GraphDiagnostic(GraphIssueCode.AnalysisLimit, GraphIssueSeverity.Warning)
    analysis.structurallyUnreachable.forEach { issues += GraphDiagnostic(GraphIssueCode.Unreachable, GraphIssueSeverity.Warning, setOf(it)) }
    // An ancestor's transition can exit a leaf. Final states and completed regions are intentional terminals.
    for (node in states.filterIsInstance<AtomicState>()) {
        val sources = ancestorsOf(node.id) + node.id
        if (node.id in analysis.reached && transitions.none { it.source in sources && it.target != node.id }) {
            issues += GraphDiagnostic(GraphIssueCode.NoExit, GraphIssueSeverity.Warning, setOf(node.id))
        }
    }
    transitions.forEachIndexed { index, transition ->
        // Timers are separate scheduled inputs; equal delays do not imply identical input identities.
        if (transition.trigger !is Trigger.After &&
            transitions.take(index).any {
                it.source == transition.source && it.trigger == transition.trigger && it.guard == GuardCondition.Unconditional
            }
        ) {
            issues += GraphDiagnostic(GraphIssueCode.ShadowedTransition, GraphIssueSeverity.Warning, setOf(transition.source), setOf(TransitionId(index)))
        }
    }
    return issues
}
