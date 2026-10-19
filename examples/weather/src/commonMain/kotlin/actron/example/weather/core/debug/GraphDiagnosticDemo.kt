package actron.example.weather.core.debug

import actron.statechart.*
import actron.timetravel.compose.HistoryGraph

/** Inspector-only copies: these deliberately broken declarations are never executed or persisted. */
enum class GraphDiagnosticDemo { Off, Warnings, Errors }

fun HistoryGraph.withDiagnosticDemo(mode: GraphDiagnosticDemo): HistoryGraph {
    if (this !is HistoryGraph.Machine || mode == GraphDiagnosticDemo.Off) return this
    val orphan = StateId("Demo.Unreachable")
    val stuck = StateId("Demo.NoExit")
    val missing = StateId("Demo.Missing")
    val demoTrigger = ActionMatcher("Demo.Action")
    val chart =
        definition.copy(
            states = definition.states + listOf(AtomicState(orphan), AtomicState(stuck)),
            transitions =
                definition.transitions +
                    listOf(
                        Transition(definition.initial, stuck, demoTrigger),
                        Transition(definition.initial, definition.initial, demoTrigger),
                    ) + if (mode == GraphDiagnosticDemo.Errors) listOf(Transition(definition.initial, missing, ActionMatcher("Demo.Invalid"))) else emptyList(),
        )
    return copy(definition = chart)
}
