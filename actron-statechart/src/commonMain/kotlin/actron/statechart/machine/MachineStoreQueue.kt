package actron.statechart.machine

import actron.core.Action
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.Volatile

/**
 * Serializes offers, group cuts and close. Exactly one drainer hands inputs to the core,
 * outside the gate, so immediate processing may safely reenter dispatch/close.
 */
internal class MachineStoreQueue<A : Action>(
    private val gate: MachineStoreGate,
    private val work: MachineStoreWork<A>,
    private val handOff: (MachineStoreSubmission<A>) -> Unit,
) {
    @Volatile
    var isClosed = false
        private set
    private var frozen = false
    private val held = ArrayDeque<MachineStoreSubmission<A>>()
    private val entering = ArrayDeque<MachineStoreSubmission<A>>()
    private var handoffs = 0
    private val changes = MutableStateFlow(0L)
    private var draining = false
    private var discardHandoffReservation: () -> Unit = {}

    val handoffChanges: StateFlow<Long> get() = changes
    val pendingHandoffs: Int get() = gate.locked { handoffs }
    val heldInputs: Int get() = gate.locked { held.size }

    fun submit(submission: MachineStoreSubmission<A>): Admission =
        gate.locked {
            if (isClosed) return@locked Admission.Closed
            val reservation = work.reserve(submission)
            if (reservation is Admission.Rejected) return@locked reservation
            queueOpen(submission)
            Admission.Accepted
        }

    fun enqueue(input: MachineInput<A>) {
        gate.locked { if (!isClosed) queueOpen(MachineStoreSubmission(input)) }
        drain()
    }

    // Called under gate: a reservation, its place in cut order and close are indivisible.
    private fun queueOpen(submission: MachineStoreSubmission<A>) {
        if (frozen) {
            held += submission
        } else {
            entering += submission
            handoffs++
        }
    }

    fun drain() {
        val elected =
            gate.locked {
                if (draining || entering.isEmpty()) {
                    false
                } else {
                    draining = true
                    true
                }
            }
        if (!elected) return
        while (true) {
            val next: () -> Boolean =
                gate.locked {
                    if (entering.isEmpty()) {
                        draining = false
                        val done: () -> Boolean = { false }
                        done
                    } else {
                        val submission = entering.removeFirst().also { discardHandoffReservation = { it.counted = false } }
                        val dispatch: () -> Boolean = {
                            handOff(submission)
                            true
                        }
                        dispatch
                    }
                }
            if (!next()) return
        }
    }

    fun handoffFailed() {
        gate.locked { draining = false }
    }

    fun handoffFinished() {
        gate.locked {
            discardHandoffReservation = {}
            handoffs--
        }
        // A StateFlow collector may resume inline, just like a per-input waiter.
        changes.update { it + 1 }
    }

    fun freeze() {
        gate.locked { if (!isClosed) frozen = true }
    }

    fun thaw() {
        gate.locked {
            // Transfer held inputs before admitting any later offer.
            handoffs += held.size
            while (held.isNotEmpty()) entering += held.removeFirst()
            frozen = false
        }
        drain()
    }

    fun close(): Boolean {
        val discarded =
            gate.locked {
                if (isClosed) return false
                isClosed = true
                val queued = held.toList() + entering.toList()
                handoffs -= entering.size
                held.clear()
                entering.clear()
                frozen = false
                work.close(queued, discardHandoffReservation)
                queued
            }
        changes.update { it + 1 }
        discarded.forEach { it.completion.complete() }
        return true
    }
}
