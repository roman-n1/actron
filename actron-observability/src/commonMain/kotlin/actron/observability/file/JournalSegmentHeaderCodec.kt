package actron.observability.file

import actron.observability.ExecutionMode
import actron.observability.MachineGroupId
import actron.observability.RuntimeSessionId

/** Header fields, independent of record bodies and of the segment reader's stop policy. */
internal object JournalSegmentHeaderCodec {
    private const val HEADER_TAG = 0

    fun encode(
        header: SegmentHeader,
        magic: ByteArray,
    ): ByteArray {
        val payload =
            ByteWriter(64)
                .apply {
                    u8(HEADER_TAG)
                    i32(header.fileFormatVersion)
                    i32(header.recordFormatVersion)
                    string(header.session.value)
                    string(header.group.value)
                    string(header.mode.name)
                    i32(header.index)
                }.toByteArray()
        return ByteWriter(magic.size + 8 + payload.size)
            .apply {
                raw(magic)
                raw(Framing.frame(payload))
            }.toByteArray()
    }

    fun decode(reader: ByteReader): SegmentHeader {
        if (reader.u8() != HEADER_TAG) throw ByteReader.Malformed("not a header")
        val fileFormatVersion = reader.i32()
        val recordFormatVersion = reader.i32()
        val session = reader.string()
        val group = reader.string()
        val mode = reader.string()
        val index = reader.i32()
        reader.expectEnd()
        return SegmentHeader(
            fileFormatVersion,
            recordFormatVersion,
            RuntimeSessionId(session),
            MachineGroupId(group),
            enumNamed<ExecutionMode>(mode),
            index,
        )
    }
}
