package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.CommandId
import actron.statechart.machine.CommandRegistration
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.MachineTime

/** Frozen payload domains projected into executable choices for one actual path. */
internal class ScenarioInputs<C : Any, A : Action, CMD : Any, E : Event>(
    private val machine: Machine<C, A, CMD, E>,
    private val actions: List<(MachineSnapshot<C>) -> List<A>>,
    private val replies: List<Pair<Set<ReplyMode>, (CMD) -> List<A>>>,
    private val extras: List<(MachineSnapshot<C>, MachineTime, List<ScenarioStep<C, A, CMD, E>>) -> List<MachineInput<A>>>,
) {
    fun choices(
        snapshot: MachineSnapshot<C>,
        now: MachineTime,
        steps: List<ScenarioStep<C, A, CMD, E>>,
    ): List<MachineInput<A>> =
        buildList {
            addAll(actions(snapshot, now))
            val commands = steps.flatMap { it.explained.decision.commands }.distinctBy { it.id }
            commands.forEach { command -> addAll(replies(command, snapshot, now, steps)) }
            snapshot.timers.entries
                .sortedWith(compareBy({ it.value.deadline }, { it.key.value }))
                .forEach { (id, timer) -> add(MachineInput.TimerFired(id, maxOf(now, timer.deadline))) }
            extras.forEach { addAll(it(snapshot, now, steps)) }
        }.distinct()

    private fun actions(
        snapshot: MachineSnapshot<C>,
        now: MachineTime,
    ): List<MachineInput<A>> {
        val declared = machine.declaredActions(snapshot).map { it.matcher }
        return actions
            .flatMap { it(snapshot) }
            .distinct()
            .filter { action -> declared.any { it.matches(action) } }
            .map { MachineInput.Dispatch(it, now) }
    }

    private fun replies(
        command: CommandRegistration<CMD>,
        snapshot: MachineSnapshot<C>,
        now: MachineTime,
        steps: List<ScenarioStep<C, A, CMD, E>>,
    ): List<MachineInput<A>> =
        buildList {
            val active = command.id in snapshot.commands
            for ((modes, values) in replies) {
                if (!accepts(active, modes)) continue
                for (action in values(command.command)) {
                    addAll(reply(command.id, active, modes, action, now, steps))
                }
            }
        }

    private fun reply(
        command: CommandId,
        active: Boolean,
        modes: Set<ReplyMode>,
        action: A,
        now: MachineTime,
        steps: List<ScenarioStep<C, A, CMD, E>>,
    ): List<MachineInput<A>> =
        buildList {
            if (active || ReplyMode.LateCommand in modes) addAll(commandResult(command, action, now, steps))
            if (!active && ReplyMode.LateAction in modes) addAll(lateAction(action, now, steps))
        }

    private fun accepts(
        active: Boolean,
        modes: Set<ReplyMode>,
    ): Boolean = if (active) ReplyMode.Active in modes else modes.any { it != ReplyMode.Active }

    private fun commandResult(
        command: CommandId,
        action: A,
        now: MachineTime,
        steps: List<ScenarioStep<C, A, CMD, E>>,
    ): List<MachineInput<A>> {
        val alreadySent =
            steps.any {
                val input = it.input
                input is MachineInput.CommandResult && input.command == command && input.action == action
            }
        return if (alreadySent) emptyList() else listOf(MachineInput.CommandResult(command, action, now))
    }

    private fun lateAction(
        action: A,
        now: MachineTime,
        steps: List<ScenarioStep<C, A, CMD, E>>,
    ): List<MachineInput<A>> {
        val alreadySent =
            steps.any {
                val input = it.input
                input is MachineInput.Dispatch && input.action == action
            }
        return if (alreadySent) emptyList() else listOf(MachineInput.Dispatch(action, now))
    }
}
