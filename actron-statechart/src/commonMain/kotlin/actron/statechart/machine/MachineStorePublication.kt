package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.core.InputAttribution
import actron.core.PluginScope
import kotlin.concurrent.Volatile

/**
 * Owns the handoff from a handled decision to the core's onState hook. Both staging and
 * publication run under the core's processing lock, including suspending plugin delivery.
 * The core remains the only snapshot writer; this object holds only the pending decision.
 */
internal class MachineStorePublication<C : Any, A : Action, CMD : Any, E : Event>(
    private val apply: (Decision<C, CMD, E>, InputAttribution) -> Unit,
    private val observe: ((DecisionObserver<C, A, CMD, E>) -> Unit) -> Unit,
    private val deliver: suspend (PluginScope<MachineSnapshot<C>, MachineInput<A>>, MachineSnapshot<C>, E) -> Unit,
) {
    private class Pending<C : Any, A : Action, CMD : Any, E : Event>(
        val decision: Decision<C, CMD, E>,
        val input: InputAttribution,
        val machineInput: MachineInput<A>,
    )

    @Volatile
    private var publishPending: suspend (PluginScope<MachineSnapshot<C>, MachineInput<A>>, MachineSnapshot<C>) -> Unit = { _, _ -> }

    fun stage(
        decision: Decision<C, CMD, E>,
        input: InputAttribution,
        machineInput: MachineInput<A>,
    ) {
        val committed = Pending(decision, input, machineInput)
        publishPending = { scope, state -> publishCommitted(scope, state, committed) }
    }

    suspend fun publish(
        scope: PluginScope<MachineSnapshot<C>, MachineInput<A>>,
        state: MachineSnapshot<C>,
    ) = publishPending(scope, state)

    private suspend fun publishCommitted(
        scope: PluginScope<MachineSnapshot<C>, MachineInput<A>>,
        state: MachineSnapshot<C>,
        committed: Pending<C, A, CMD, E>,
    ) {
        val decision = committed.decision
        check(decision.snapshot === state) { "[Actron] MachineStore committed a snapshot that is not the pending decision's" }
        // Consume before callbacks: neither suspension nor reentry may publish this decision twice.
        publishPending = { _, _ -> }
        apply(decision, committed.input)
        observe { it.onCommitted(committed.input, committed.machineInput, decision) }
        // Every observer records the sending decision before any group route delivers it.
        observe { observer ->
            if (observer is MachineGroup.Member<*, *, *, *>) {
                @Suppress("UNCHECKED_CAST")
                (observer as MachineGroup.Member<C, A, CMD, E>).send(committed.input, committed.machineInput, decision)
            }
        }
        // Effect-major order is shared by transient and retained effects and every patched plugin.
        for (effect in decision.effects) deliver(scope, state, effect.event)
    }
}
