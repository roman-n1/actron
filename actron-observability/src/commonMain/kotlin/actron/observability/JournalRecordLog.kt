package actron.observability

import kotlin.time.TimeMark

/**
 * Allocates the group's order and each Store's order, and retains their common record stream.
 * Every access is guarded by the session lifecycle lock; snapshots never expose the ring.
 */
internal class JournalRecordLog(
    private val session: RuntimeSessionId,
    private val group: MachineGroupId,
    private val mode: ExecutionMode,
    private val capacity: Int,
    private val startedAt: TimeMark,
) {
    private val records = ArrayDeque<JournalRecord<*, *, *>>()
    private val storeSeqs = mutableMapOf<StoreInstanceId, Long>()

    var published: Long = 0
        private set

    var evicted: Long = 0
        private set

    val retained: Int get() = records.size

    fun snapshot(): List<JournalRecord<*, *, *>> = records.toList()

    fun append(
        store: RecordSubject,
        entry: JournalEntry<*, *, *>,
    ): JournalRecord<*, *, *> {
        val groupSeq = GroupSeq(++published)
        val storeSeq: RecordOrdinal =
            when (store) {
                RecordSubject.Session -> {
                    RecordOrdinal.Session
                }

                is StoreInstanceId -> {
                    val next = (storeSeqs[store] ?: 0L) + 1
                    storeSeqs[store] = next
                    StoreSeq(next)
                }
            }
        val record =
            JournalRecord(
                formatVersion = JOURNAL_FORMAT_VERSION,
                session = session,
                group = group,
                store = store,
                mode = mode,
                groupSeq = groupSeq,
                storeSeq = storeSeq,
                elapsed = startedAt.elapsedNow(),
                entry = entry,
            )
        retain(record)
        return record
    }

    private fun retain(record: JournalRecord<*, *, *>) {
        if (capacity == 0) return
        while (records.size >= capacity) {
            records.removeFirst()
            evicted++
        }
        records.addLast(record)
    }
}
