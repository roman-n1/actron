package actron.statechart.machine

import actron.core.Action
import actron.core.ActronStoreDsl
import actron.core.Event
import actron.statechart.CompoundState
import actron.statechart.ParallelState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.StateNode
import actron.statechart.Transition

/** One machine with simultaneously active typed regions. Shared inputs are broadcast to
 * all matching regions in one decision; each region owns its commands and timers.
 */
fun <C : Any, A : Action, CMD : Any, E : Event> parallelStateMachine(
    id: DefinitionId,
    version: DefinitionVersion,
    build: ParallelStateMachineBuilder<C, A, CMD, E>.() -> Unit,
): Machine<C, A, CMD, E> = ParallelStateMachineBuilder<C, A, CMD, E>().apply(build).build(id, version)

@ActronStoreDsl
class ParallelStateMachineBuilder<C : Any, A : Action, CMD : Any, E : Event> internal constructor() {
    private val root = StateId("Parallel")
    private val implementation = MachineBuilder<C, A, CMD, E>()
    private val nodes = mutableListOf<StateNode>(ParallelState(root))
    private val transitions = mutableListOf<Transition>()
    private val regions = mutableSetOf<StateId>()

    /** Names namespace state types (e.g. Current.Loading and Hourly.Loading) and are persisted. */
    fun <S : Any> region(
        name: String,
        initial: S,
        readState: (C) -> S,
        writeState: (C, S) -> C,
        build: TypedStateMachineBuilder<C, S, A, CMD, E>.() -> Unit,
    ) {
        val region = StateId(name)
        require(region != root && regions.add(region)) { "Region names must be unique: $name" }
        val builder = TypedStateMachineBuilder(readState, writeState, implementation, transitions, nodes, region, "$name.").apply(build)
        nodes += CompoundState(region, initial = builder.finish(initial), parent = root)
    }

    internal fun build(
        id: DefinitionId,
        version: DefinitionVersion,
    ): Machine<C, A, CMD, E> {
        require(regions.size >= 2) { "A parallel machine needs at least two regions" }
        return Machine(id, version, StateChartDefinition(root, nodes.toList(), transitions.toList()), implementation)
    }
}
