@file:OptIn(InternalActronApi::class)

package actron.core

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Owns the root coroutine lifetime and the identity used to reject self-awaiting operations. */
internal class StoreExecution(
    private val context: () -> CoroutineContext,
    private val contextUsed: () -> Unit,
    private val onFailure: (Throwable, InputAttribution) -> Unit,
    private val onClosed: () -> Unit,
) {
    // Carried by handlers, plugin hooks and transactions, but not ordinary launches.
    val inside: CoroutineContext = InsideStore(this)

    private val scopeDelegate =
        lazy {
            contextUsed()
            val scope =
                CoroutineScope(
                    context() + SupervisorJob(context()[Job]) + OwnedByStore(this) +
                        CoroutineExceptionHandler { origin, failure ->
                            onFailure(failure, origin[InputOrigin]?.input ?: InputAttribution.Unattributed)
                        },
                )
            // Traced once all Store coroutines have ended, after close or parent cancellation.
            scope.coroutineContext[Job]?.invokeOnCompletion { onClosed() }
            scope
        }

    val scope: CoroutineScope by scopeDelegate

    // Inspecting idle work must not consume the configured context or start a passive Store.
    // A cancelled root still owns all children until their cancellation cleanup completes.
    fun closingJobs(): List<Job> {
        if (!scopeDelegate.isInitialized()) return emptyList()
        val root = scope.coroutineContext[Job] ?: return emptyList()
        return if (root.isCancelled && !root.isCompleted) listOf(root) else emptyList()
    }

    suspend fun checkOutside(name: String) {
        check(currentCoroutineContext()[InsideStore]?.owner !== this) {
            "[Actron] $name must not be called from a handler, plugin hook or transaction of the same Store: it would wait for itself. Call it from a launch {} or from outside the Store"
        }
    }

    // Under the lock use the processing input; a launch instead carries its own stable origin.
    suspend fun attribution(processing: () -> InputAttribution): InputAttribution = if (currentCoroutineContext()[InsideStore]?.owner === this) processing() else currentInputId()

    fun close() {
        scope.cancel()
    }

    suspend fun closeAndJoin() {
        check(currentCoroutineContext()[OwnedByStore]?.owner !== this) {
            "[Actron] closeAndJoin must not be called from a coroutine owned by the same Store: it would wait for itself. Use close() instead"
        }
        close()
        scope.coroutineContext[Job]?.join()
    }

    // Unlike InsideStore, this marker also follows ordinary launches and callback collectors.
    private class OwnedByStore(
        val owner: StoreExecution,
    ) : AbstractCoroutineContextElement(OwnedByStore) {
        companion object Key : CoroutineContext.Key<OwnedByStore>
    }

    private class InsideStore(
        val owner: StoreExecution,
    ) : AbstractCoroutineContextElement(InsideStore) {
        companion object Key : CoroutineContext.Key<InsideStore>
    }
}
