package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.ActionMatcher
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.MachineTime
import actron.statechart.machine.availableActions

inline fun <C : Any, reified A : Action> actionGenerator(
    name: String,
    noinline generate: (MachineSnapshot<C>, MachineTime) -> List<A>,
): ActionGenerator<C, A> = ActionGenerator(ActionMatcher.of<A>(name), generate)

/**
 * Uses active declarations, payload domains and custom constraints. includeBlocked keeps false
 * guard branches observable; otherwise the selection query filters guards (never reducer/IO).
 * Explicit extraInputs supplies command results or other external responses. Timers use deadlines.
 */
fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.inputGenerator(
    generators: List<ActionGenerator<C, out A>>,
    includeBlocked: Boolean = true,
    includeTimers: Boolean = true,
    constraint: (MachineSnapshot<C>, A) -> Boolean = { _, _ -> true },
    extraInputs: MachineInputGenerator<C, A> = MachineInputGenerator { _, _ -> emptyList() },
): MachineInputGenerator<C, A> =
    MachineInputGenerator { snapshot, now ->
        val declared = declaredActions(snapshot).map { it.matcher }
        val actions =
            generators
                .flatMap { it.actions(snapshot, now) }
                .filter { action -> declared.any { it.matches(action) } }
                .distinct()
                .filter { constraint(snapshot, it) }
        val selected = if (includeBlocked) actions else availableActions(snapshot, actions).executable.map { it.action }
        selected.map { MachineInput.Dispatch(it, now) } + extraInputs.inputs(snapshot, now) +
            if (includeTimers) {
                snapshot.timers.entries
                    .sortedWith(compareBy({ it.value.deadline }, { it.key.value }))
                    .map { (id, timer) -> MachineInput.TimerFired(id, maxOf(now, timer.deadline)) }
            } else {
                emptyList()
            }
    }
