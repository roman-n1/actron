package actron.statechart.machine

import kotlinx.coroutines.sync.Mutex

/** Non-suspending bookkeeping only. Store calls and user callbacks run after releasing it. */
internal class MachineStoreGate {
    private val mutex = Mutex()

    inline fun <T : Any> locked(block: () -> T): T {
        while (!mutex.tryLock()) {
            // The holder only updates queue/tracking state; no user code or suspension.
        }
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }
}
