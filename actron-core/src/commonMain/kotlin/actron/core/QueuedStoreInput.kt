@file:OptIn(InternalActronApi::class)

package actron.core

import kotlinx.coroutines.CancellationException
import kotlin.concurrent.Volatile

// A queued input whose coroutine may be cancelled before it gets the lock, by close() or by a
// clear of the pending actions. Once the lock is held, the processing traces its own end.
internal class QueuedStoreInput(
    private val input: InputId,
    private val discard: (InputId, DiscardReason) -> Unit,
) {
    @Volatile
    var locked = false

    fun onCancellation(cause: CancellationException) {
        if (!locked) {
            val reason = if (cause is PendingDispatchCleared) cause.reason else DiscardReason.StoreClosed
            discard(input, reason)
        }
    }
}

internal class PendingDispatchCleared(
    val reason: DiscardReason,
) : CancellationException("[Actron] Pending action cleared")
