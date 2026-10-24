package actron.statechart.machine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Coroutine ownership shared by an adapter's actor, effects and collectors, including cleanup. */
internal class StoreLifetime(
    parent: CoroutineContext,
) {
    val job: Job = SupervisorJob(parent[Job])
    val marker: CoroutineContext = Owner(this)
    val scope: CoroutineScope = CoroutineScope(parent + job + marker)

    suspend fun checkOutside() {
        check(currentCoroutineContext()[Owner]?.lifetime !== this) {
            "[Actron] closeAndJoin must not be called from a coroutine owned by the same Store: it would wait for itself. Use close() instead"
        }
    }

    private class Owner(
        val lifetime: StoreLifetime,
    ) : AbstractCoroutineContextElement(Owner) {
        companion object Key : CoroutineContext.Key<Owner>
    }
}
