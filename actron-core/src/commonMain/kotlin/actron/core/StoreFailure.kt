package actron.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Rethrows [t] when it must not be handled by `recover {}` or the exception handler: fatal
 * errors (non-[Exception] throwables) and the cancellation of the current coroutine, that is
 * the Store closing or a state exiting. A [CancellationException] thrown while the current
 * coroutine is still active, for example a `withTimeout {}` in a handler that expired or an
 * `await()` on a cancelled `Deferred`, is an ordinary failure of that handler and is handled.
 */
internal suspend fun rethrowIfNonRecoverable(t: Throwable) {
    if (t !is Exception) throw t
    if (t is CancellationException && !currentCoroutineContext().isActive) throw t
}

/**
 * Internal signal that must bypass DSL recovery: hook failure, failed recovery or a broken runtime
 * invariant. It deliberately is not an Exception, so the recovery guard rethrows it.
 *
 * With handlerFailed=false, original is still owed an outer exception-handler report. With
 * handlerFailed=true, original came from that handler itself: it must escape without a second
 * report or FailureReported trace. This is not the classification of a user fatal Throwable.
 */
internal class StoreBoundaryFailure(
    val original: Throwable,
    val handlerFailed: Boolean = false,
) : Throwable(original)

// Unwrap only our one boundary layer; arbitrary user cause chains stay intact.
internal fun Throwable.boundaryCause(): Throwable = if (this is StoreBoundaryFailure) original else this
