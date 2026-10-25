package actron.statechart.machine

import actron.core.Action
import kotlinx.coroutines.CompletableDeferred

/** One accepted offer; its reservation and completion follow it through cut and core queues. */
internal class MachineStoreSubmission<A : Action>(
    val input: MachineInput<A>,
    var counted: Boolean = false,
    val completion: MachineStoreCompletion = MachineStoreCompletion.Detached,
)

internal sealed interface MachineStoreCompletion {
    fun complete()

    fun fail(error: Throwable)

    data object Detached : MachineStoreCompletion {
        override fun complete() {}

        override fun fail(error: Throwable) {}
    }

    class Awaited(
        val deferred: CompletableDeferred<Unit>,
    ) : MachineStoreCompletion {
        override fun complete() {
            deferred.complete(Unit)
        }

        override fun fail(error: Throwable) {
            deferred.completeExceptionally(error)
        }
    }
}
