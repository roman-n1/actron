@file:OptIn(actron.core.InternalActronApi::class)

package actron.statechart.machine

import actron.core.Action
import actron.core.InputId
import actron.core.InputKind
import actron.core.StoreTrace

/**
 * Owns admission reservations and the association with core input IDs. Queue operations may
 * acquire tracking while holding the cut gate; traces never acquire the cut gate while here.
 */
internal class MachineStoreWork<A : Action>(
    private val admission: AdmissionPolicy,
) {
    private val tracking = MachineStoreGate()
    private var waiting = 0
    private val submitted = mutableMapOf<InputId, MachineStoreSubmission<A>>()
    private var registerAccepted: (InputId, MachineInput<A>) -> Unit = { _, _ -> }

    val pendingAdmissions: Int get() = tracking.locked { waiting }

    fun reserve(submission: MachineStoreSubmission<A>): Admission =
        tracking.locked {
            val policy = admission
            if (policy is AdmissionPolicy.Bounded && waiting >= policy.maxPending) {
                Admission.Rejected(waiting, policy.maxPending)
            } else {
                waiting++
                submission.counted = true
                Admission.Accepted
            }
        }

    fun track(trace: StoreTrace<*, MachineInput<A>, *>) {
        val completions =
            tracking.locked {
                when (trace) {
                    is StoreTrace.InputAccepted -> {
                        val kind = trace.kind
                        if (kind is InputKind.Dispatch) registerAccepted(trace.input, kind.action)
                        emptyList()
                    }

                    is StoreTrace.ProcessingStarted -> {
                        submitted[trace.input]?.let(::releaseReservation)
                        emptyList()
                    }

                    is StoreTrace.InputDiscarded -> {
                        finished(trace.input, release = true)
                    }

                    is StoreTrace.ProcessingFinished -> {
                        finished(trace.input, release = false)
                    }

                    StoreTrace.StoreClosed -> {
                        // Only this terminal trace proves all inner jobs/observers have ended.
                        val remaining = submitted.values.toList()
                        submitted.clear()
                        remaining.forEach(::releaseReservation)
                        remaining.map { it.completion }
                    }

                    else -> {
                        emptyList()
                    }
                }
            }
        // Completion can resume an immediate caller. Never do that under tracking.
        completions.forEach { it.complete() }
    }

    private fun finished(
        id: InputId,
        release: Boolean,
    ): List<MachineStoreCompletion> =
        submitted.remove(id)?.let {
            if (release) releaseReservation(it)
            listOf(it.completion)
        } ?: emptyList()

    // Called only while tracking is held, including discards that never started a handler.
    private fun releaseReservation(submission: MachineStoreSubmission<A>) {
        if (submission.counted) {
            submission.counted = false
            waiting--
        }
    }

    fun dispatch(
        submission: MachineStoreSubmission<A>,
        dispatch: (MachineInput<A>) -> Unit,
    ) {
        tracking.locked {
            registerAccepted = { id, input -> if (submission.input === input) submitted[id] = submission }
        }
        try {
            dispatch(submission.input)
        } finally {
            tracking.locked { registerAccepted = { _, _ -> } }
        }
    }

    fun failed(
        submission: MachineStoreSubmission<A>,
        failure: Throwable,
    ) {
        tracking.locked { submitted.entries.removeAll { it.value === submission } }
        submission.completion.fail(failure)
    }

    /** The caller holds the cut gate, so close and reservation are one ordered decision. */
    fun close(
        queued: List<MachineStoreSubmission<A>>,
        discardHandoffReservation: () -> Unit,
    ) {
        tracking.locked {
            waiting = 0
            queued.forEach { it.counted = false }
            discardHandoffReservation()
            submitted.values.forEach { it.counted = false }
        }
    }
}
