package actron.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive

/**
 * Owns input identity, pending work and processing outcomes. Atomic admission is thread safe;
 * processing, commits and revisions run under the Store's transition mutex.
 */
@OptIn(InternalActronApi::class)
internal class StoreInputs<S : State, A : Action, E : Event>(
    private val hasProbes: () -> Boolean,
    private val record: (StoreTrace<S, A, E>) -> Unit,
) {
    private val nextId = MutableStateFlow(0L)
    private val pending = MutableStateFlow(0)
    private val startup = MutableStateFlow<StartupState>(StartupState.NotRequested)
    private var ordinal = 0L
    private var revision = 0L
    private var processing: InputProcessing = InputProcessing.Idle

    val pendingCount: Int get() = pending.value
    val current: InputProcessing get() = processing

    suspend fun awaitPending() {
        pending.first { it <= 0 }
    }

    fun requestStartup(): InputId {
        val previous = startup.value
        if (previous is StartupState.Requested) return previous.input
        val candidate = allocateId()
        if (startup.compareAndSet(StartupState.NotRequested, StartupState.Requested(candidate))) {
            accepted(candidate, InputKind.Startup)
            return candidate
        }
        return (startup.value as StartupState.Requested).input
    }

    fun accept(kind: InputKind<A>): InputId {
        val input = allocateId()
        accepted(input, kind)
        return input
    }

    fun discard(
        input: InputId,
        reason: DiscardReason,
    ) {
        pending.update { it - 1 }
        trace { StoreTrace.InputDiscarded(input, reason) }
    }

    fun commit(
        state: S,
        nextState: S,
        publish: () -> Unit,
    ) {
        val input = processing.commit()
        publish()
        val currentRevision = ++revision
        trace { StoreTrace.StateCommitted(input, currentRevision, state, nextState) }
    }

    private fun allocateId(): InputId = InputId(nextId.updateAndGet { it + 1 })

    private fun accepted(
        input: InputId,
        kind: InputKind<A>,
    ) {
        pending.update { it + 1 }
        trace { StoreTrace.InputAccepted(input, kind) }
    }

    private inline fun trace(build: () -> StoreTrace<S, A, E>) {
        if (hasProbes()) record(build())
    }

    private sealed interface StartupState {
        data object NotRequested : StartupState

        data class Requested(
            val input: InputId,
        ) : StartupState
    }

    // Runs the processing of [input] under `mutex` and traces its start and its outcome. The
    // handlers, commits, recovery and reports inside [block] find it in `processing`.
    suspend fun process(
        input: InputId,
        block: suspend () -> Unit,
    ) {
        val outer = processing
        val current = Processing(input, ++ordinal)
        processing = current
        var result: () -> ProcessingOutcome = current::outcome
        try {
            // A probe (or a rethrowing exception handler) can abort this boundary too.
            // It still owes the input a terminal trace and must release pending work.
            trace { StoreTrace.ProcessingStarted(current.input, current.ordinal) }
            block()
        } catch (t: Throwable) {
            val closed = t is CancellationException && !currentCoroutineContext().isActive
            val failed = if (closed) ProcessingOutcome.Cancelled else ProcessingOutcome.Failed(t.boundaryCause())
            result = { failed }
            throw t
        } finally {
            processing = outer
            val outcome = result()
            pending.update { it - 1 }
            trace { StoreTrace.ProcessingFinished(current.input, current.ordinal, outcome) }
        }
    }
}
