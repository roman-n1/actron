package actron.observability

import kotlinx.coroutines.sync.Mutex

/**
 * The session's single publication gate. The same short, non-suspending section protects
 * record allocation, retention, queue admission, counters and the transition to stopped.
 */
internal class JournalSessionLifecycle {
    private val lock = Mutex()
    private var stopped = false

    var publishedAfterStop: Long = 0
        private set

    inline fun publish(block: () -> Unit) {
        locked {
            if (stopped) {
                publishedAfterStop++
            } else {
                block()
            }
        }
    }

    suspend fun close(
        records: JournalRecordLog,
        delivery: JournalDeliveryQueue,
    ) {
        locked {
            if (!stopped) {
                stopped = true
                delivery.finishWith(
                    buildList {
                        addAll(delivery.closingGap(records))
                        add(records.append(RecordSubject.Session, JournalEntry.RecordingStopped))
                    },
                )
            }
        }
        delivery.close()
    }

    inline fun <T : Any> locked(block: () -> T): T {
        while (!lock.tryLock()) {
            // Spin: the holder is inside a section of a few field writes.
        }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
