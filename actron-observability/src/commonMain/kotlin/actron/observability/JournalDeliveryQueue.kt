package actron.observability

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * One bounded queue and one writer, owned by the caller's scope. Admission and gap accounting
 * run under the session lifecycle lock; suspending delivery and draining run outside it.
 */
internal class JournalDeliveryQueue(
    scope: CoroutineScope,
    capacity: Int,
    deliver: suspend (JournalRecord<*, *, *>) -> Unit,
) {
    private val queue = Channel<JournalRecord<*, *, *>>(capacity)
    private var pendingGap = 0L
    private val finalRecords = CompletableDeferred<List<JournalRecord<*, *, *>>>()

    var droppedForSinks: Long = 0
        private set

    private val writer =
        scope.launch {
            for (record in queue) deliver(record)
            for (record in finalRecords.await()) deliver(record)
        }

    fun offer(
        records: JournalRecordLog,
        store: RecordSubject,
        entry: JournalEntry<*, *, *>,
    ) {
        if (pendingGap > 0) {
            val gap = records.append(RecordSubject.Session, JournalEntry.JournalGap(pendingGap))
            if (queue.trySend(gap).isSuccess) {
                pendingGap = 0
            } else {
                // The writer may free a slot between the two offers; the record must not take
                // it, or a sink would see a hole with no gap in front of the next record.
                lost()
                records.append(store, entry)
                lost()
                return
            }
        }
        val record = records.append(store, entry)
        if (queue.trySend(record).isFailure) lost()
    }

    fun closingGap(records: JournalRecordLog): List<JournalRecord<*, *, *>> {
        if (pendingGap <= 0L) return emptyList()
        val gap = records.append(RecordSubject.Session, JournalEntry.JournalGap(pendingGap))
        pendingGap = 0
        return listOf(gap)
    }

    fun finishWith(closing: List<JournalRecord<*, *, *>>) {
        // Published under the admission gate before any caller closes the channel. The writer
        // reads this signal only after draining the channel, so no sink runs inside that gate.
        check(finalRecords.complete(closing))
    }

    suspend fun close() {
        // Idempotent across callers. Final delivery belongs to the existing writer, so cancelling
        // one join cannot lose the stop record or interrupt another caller's close.
        queue.close()
        writer.join()
    }

    private fun lost() {
        pendingGap++
        droppedForSinks++
    }
}
