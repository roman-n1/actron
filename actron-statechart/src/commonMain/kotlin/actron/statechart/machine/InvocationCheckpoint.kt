package actron.statechart.machine

import actron.core.ExperimentalActronApi

/** Executor data for one invocation. The host captures both sides at the same decision boundary. */
@ExperimentalActronApi
data class InvocationCheckpoint<PC : Any, PCMD : Any, CC : Any, CCMD : Any>(
    val parent: ExecutorCheckpoint<PC, PCMD>,
    val child: ChildCheckpoint<CC, CCMD> = ChildCheckpoint.Dormant,
) {
    val snapshot: InvocationSnapshot<PC, CC>
        get() =
            InvocationSnapshot(
                parent.snapshot,
                when (val current = child) {
                    ChildCheckpoint.Dormant -> ChildInvocation.Dormant
                    is ChildCheckpoint.Active -> ChildInvocation.Active(current.owner, current.checkpoint.snapshot)
                },
            )
}

@ExperimentalActronApi
sealed interface ChildCheckpoint<out C : Any, out CMD : Any> {
    data object Dormant : ChildCheckpoint<Nothing, Nothing>

    data class Active<C : Any, CMD : Any>(
        val owner: ActivationId,
        val checkpoint: ExecutorCheckpoint<C, CMD>,
    ) : ChildCheckpoint<C, CMD>
}
