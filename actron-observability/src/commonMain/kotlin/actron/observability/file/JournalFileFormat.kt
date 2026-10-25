package actron.observability.file

import actron.observability.ExecutionMode
import actron.observability.JOURNAL_FORMAT_VERSION
import actron.observability.JournalEntry
import actron.observability.JournalRecord
import actron.observability.MachineGroupId
import actron.observability.Payload
import actron.observability.RuntimeSessionId

/**
 * Version of the segment layout below. Bumped when the framing, the header or an encoding
 * changes; the record model has its own [JOURNAL_FORMAT_VERSION], written in every header. A
 * reader that meets a newer version of either refuses the segment, never guesses.
 */
const val JOURNAL_FILE_FORMAT_VERSION: Int = 1

/**
 * The binary layout of a journal segment, and the encoding of its records.
 *
 * A segment is `KOMAJRNL`, a header frame, record frames, and, once the segment is finished, an
 * end frame. Every frame is `[i32 length][i32 crc32][payload]` with the CRC-32 of the payload,
 * so a partial write (a crash) and a damaged byte are told apart from a record; the end frame
 * has length 0, so a segment without it was still being written. The header carries this
 * version, [JOURNAL_FORMAT_VERSION], the session, the group, the mode and the segment's index;
 * a record frame carries the store, the sequence numbers, the elapsed time and the entry.
 *
 * Payloads are written as what the policy kept: a projection's label and fields, `omitted`,
 * `unavailable`, or, for a retained object, its `toString()`, which reads back as
 * [Payload.Described]; a file holds no objects. Failures keep type, message, cause and
 * suppressed. Integers are big-endian, strings a byte length and UTF-8, durations whole
 * nanoseconds, enumerations their names (the wire vocabulary).
 */
object JournalFileFormat {
    /** The first eight bytes of every segment. */
    val MAGIC: ByteArray = "KOMAJRNL".encodeToByteArray()

    /** The end frame: a length of zero and the CRC of nothing; see [Framing.END]. */
    val END: ByteArray get() = Framing.END

    /** A frame longer than this is corruption, whatever its checksum says; see [Framing.MAX_FRAME_BYTES]. */
    const val MAX_FRAME_BYTES: Int = Framing.MAX_FRAME_BYTES

    /** The extension of segment names. */
    const val EXTENSION: String = ".journal"

    /** `<session>-<index, six digits>.journal`. */
    fun segmentName(
        session: RuntimeSessionId,
        index: Int,
    ): String {
        require(index >= 0) { "[Actron] A segment index is not negative" }
        return "${session.value}-${index.toString().padStart(6, '0')}$EXTENSION"
    }

    /** Reports the session and index once; false for a name that is not a segment. */
    fun parseSegmentName(
        name: String,
        accept: (RuntimeSessionId, Int) -> Unit,
    ): Boolean {
        if (!name.endsWith(EXTENSION)) return false
        val stem = name.removeSuffix(EXTENSION)
        val dash = stem.lastIndexOf('-')
        if (dash <= 0) return false
        val index = stem.substring(dash + 1).takeIf { it.length == 6 }?.toIntOrNull() ?: return false
        accept(RuntimeSessionId(stem.substring(0, dash)), index)
        return true
    }

    internal fun namedSegments(
        storage: SegmentStorage,
        owner: RuntimeSessionId,
    ): List<Pair<Int, String>> =
        buildList {
            for (info in storage.list()) {
                parseSegmentName(info.name) { id, index ->
                    if (id == owner) add(index to info.name)
                }
            }
        }

    /** The bytes a segment begins with: the magic and the header frame. */
    fun header(
        session: RuntimeSessionId,
        group: MachineGroupId,
        mode: ExecutionMode,
        index: Int,
    ): ByteArray = header(session, group, mode, index, JOURNAL_FILE_FORMAT_VERSION, JOURNAL_FORMAT_VERSION)

    // With explicit versions, so tests can write what a newer writer would.
    internal fun header(
        session: RuntimeSessionId,
        group: MachineGroupId,
        mode: ExecutionMode,
        index: Int,
        fileFormatVersion: Int = JOURNAL_FILE_FORMAT_VERSION,
        recordFormatVersion: Int = JOURNAL_FORMAT_VERSION,
    ): ByteArray =
        JournalSegmentHeaderCodec.encode(
            SegmentHeader(fileFormatVersion, recordFormatVersion, session, group, mode, index),
            MAGIC,
        )

    /** The frame of [record]: its store, sequence numbers, elapsed time and entry, framed. */
    fun frame(record: JournalRecord<*, *, *>): ByteArray = Framing.frame(JournalRecordCodec.encode(record, JOURNAL_FORMAT_VERSION))

    // What a writer of [recordFormatVersion] wrote, so tests can write what an earlier one did.
    internal fun frame(
        record: JournalRecord<*, *, *>,
        recordFormatVersion: Int,
    ): ByteArray = Framing.frame(JournalRecordCodec.encode(record, recordFormatVersion))

    /**
     * Reads [bytes] as the segment [name]: the header, the records that can be read, and what
     * stopped the reading, if anything. Never throws on damaged bytes: the records returned are
     * exactly the frames whose checksum and encoding held, in order, and the mark says why the
     * rest was not read.
     */
    fun decodeSegment(
        name: String,
        bytes: ByteArray,
    ): DecodedSegment {
        val read = Framing.read(name, bytes, MAGIC)
        val frames = read.frames
        if (frames.isEmpty()) {
            // Cut right after the magic: the header was never written, nothing was dropped.
            val mark = read.ending.beforeHeader(name, MAGIC.size)
            return DecodedSegment(JournalPrefix.Unreadable, emptyList(), SegmentEnding.Stopped(mark))
        }
        val header =
            try {
                JournalSegmentHeaderCodec.decode(ByteReader(frames[0].payload))
            } catch (e: ByteReader.Malformed) {
                return DecodedSegment(JournalPrefix.Unreadable, emptyList(), SegmentEnding.Stopped(SegmentMark.Corrupt(name, frames[0].offset, 0, "header: ${e.message}")))
            } catch (e: IllegalArgumentException) {
                return DecodedSegment(JournalPrefix.Unreadable, emptyList(), SegmentEnding.Stopped(SegmentMark.Corrupt(name, frames[0].offset, 0, "header: ${e.message}")))
            }
        if (header.fileFormatVersion > JOURNAL_FILE_FORMAT_VERSION || header.recordFormatVersion > JOURNAL_FORMAT_VERSION) {
            return DecodedSegment(header, emptyList(), SegmentEnding.Stopped(SegmentMark.UnsupportedFormat(name, header.fileFormatVersion, header.recordFormatVersion)))
        }
        val records = mutableListOf<JournalRecord<Nothing, Nothing, Nothing>>()
        for (frame in frames.drop(1)) {
            val record =
                try {
                    JournalRecordCodec.decode(ByteReader(frame.payload), header)
                } catch (e: ByteReader.Malformed) {
                    return DecodedSegment(header, records, SegmentEnding.Stopped(SegmentMark.Corrupt(name, frame.offset, records.size, "record: ${e.message}")))
                } catch (e: IllegalArgumentException) {
                    return DecodedSegment(header, records, SegmentEnding.Stopped(SegmentMark.Corrupt(name, frame.offset, records.size, "record: ${e.message}")))
                }
            records += record
        }
        // The framing counted the header among the frames it read; records do not include it.
        return DecodedSegment(header, records, read.ending.countRecords(records.size))
    }
}

/**
 * What the header of a segment says.
 */
sealed interface JournalPrefix {
    fun withHeader(accept: (SegmentHeader) -> Unit)

    data object Unreadable : JournalPrefix {
        override fun withHeader(accept: (SegmentHeader) -> Unit) = Unit
    }
}

data class SegmentHeader(
    val fileFormatVersion: Int,
    val recordFormatVersion: Int,
    val session: RuntimeSessionId,
    val group: MachineGroupId,
    val mode: ExecutionMode,
    val index: Int,
) : JournalPrefix {
    override fun withHeader(accept: (SegmentHeader) -> Unit) = accept(this)
}

/**
 * A segment as read: its header (when it had a readable one), the records read in order, the
 * mark that says where the reading stopped and why, and whether the segment carried its end
 * frame. A finished segment without a mark was read whole.
 */
data class DecodedSegment(
    val prefix: JournalPrefix,
    val records: List<JournalRecord<Nothing, Nothing, Nothing>>,
    val ending: SegmentEnding,
) {
    val finished: Boolean get() = ending.finished
}

/**
 * What a reader found instead of, or after, records. Marks are never thrown; they are part of
 * what was read, in its order.
 */
sealed interface SegmentMark {
    /** The bytes do not begin with the segment magic. */
    data class NotASegment(
        val segment: String,
        val reason: String,
    ) : SegmentMark

    /** The segment was written by a newer format or a newer record model; nothing of it is read. */
    data class UnsupportedFormat(
        val segment: String,
        val fileFormatVersion: Int,
        val recordFormatVersion: Int,
    ) : SegmentMark

    /**
     * A frame at [offset] failed its checksum or its encoding, or bytes follow the end frame; the
     * segment is not read past it. [recordsRead] frames before it were.
     */
    data class Corrupt(
        val segment: String,
        val offset: Int,
        val recordsRead: Int,
        val reason: String,
    ) : SegmentMark

    /**
     * The segment ends inside a frame: a write was cut short, by a crash or a full disk. The
     * [trailingBytes] of the partial frame are dropped; [recordsRead] frames before it were read.
     */
    data class TruncatedTail(
        val segment: String,
        val recordsRead: Int,
        val trailingBytes: Int,
    ) : SegmentMark

    /**
     * The segment ends at a frame boundary without its end frame: it was still being written, by
     * a sink that never closed. Nothing was dropped.
     */
    data class Unfinished(
        val segment: String,
        val recordsRead: Int,
    ) : SegmentMark

    /** Segments [fromIndex]..[toIndex] of [session] are absent: rotated away, pruned or lost. */
    data class MissingSegments(
        val session: RuntimeSessionId,
        val fromIndex: Int,
        val toIndex: Int,
    ) : SegmentMark

    /**
     * Records with the sequence numbers between [afterGroupSeq] and [nextGroupSeq] are absent
     * and no [JournalEntry.JournalGap] accounts for them: they were in a segment or a frame that
     * could not be read.
     */
    data class SequenceHole(
        val session: RuntimeSessionId,
        val afterGroupSeq: Long,
        val nextGroupSeq: Long,
    ) : SegmentMark {
        val missing: Long get() = nextGroupSeq - afterGroupSeq - 1
    }
}
