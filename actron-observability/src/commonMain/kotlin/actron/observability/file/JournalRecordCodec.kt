package actron.observability.file

import actron.observability.GroupSeq
import actron.observability.JournalRecord
import actron.observability.RecordOrdinal
import actron.observability.RecordSubject
import actron.observability.StoreInstanceId
import actron.observability.StoreSeq
import actron.observability.file.JournalExecutionCodec.duration

/** The envelope common to every journal record; entry tags precede it on the wire. */
internal object JournalRecordCodec {
    fun encode(
        record: JournalRecord<*, *, *>,
        recordFormatVersion: Int,
    ): ByteArray {
        val codec = JournalEntryCodecs.forEntry(record.entry)
        return ByteWriter()
            .apply {
                u8(codec.tagOf(record.entry))
                when (val subject = record.store) {
                    RecordSubject.Session -> {
                        bool(false)
                    }

                    is StoreInstanceId -> {
                        bool(true)
                        string(subject.value)
                    }
                }
                i64(record.groupSeq.value)
                when (val ordinal = record.storeSeq) {
                    RecordOrdinal.Session -> {
                        bool(false)
                    }

                    is StoreSeq -> {
                        bool(true)
                        i64(ordinal.value)
                    }
                }
                duration(record.elapsed)
                with(codec) { entry(record.entry, recordFormatVersion) }
            }.toByteArray()
    }

    fun decode(
        reader: ByteReader,
        header: SegmentHeader,
    ): JournalRecord<Nothing, Nothing, Nothing> {
        val tag = reader.u8()
        val store: RecordSubject = if (reader.bool()) StoreInstanceId(reader.string()) else RecordSubject.Session
        val groupSeq = GroupSeq(reader.i64())
        val storeSeq: RecordOrdinal = if (reader.bool()) StoreSeq(reader.i64()) else RecordOrdinal.Session
        if ((store is StoreInstanceId) != (storeSeq is StoreSeq)) throw ByteReader.Malformed("record subject and Store ordinal differ")
        val elapsed = reader.duration()
        val entry = with(JournalEntryCodecs.forTag(tag)) { reader.entry(tag, header.recordFormatVersion) }
        reader.expectEnd()
        return JournalRecord(header.recordFormatVersion, header.session, header.group, store, header.mode, groupSeq, storeSeq, elapsed, entry)
    }
}
