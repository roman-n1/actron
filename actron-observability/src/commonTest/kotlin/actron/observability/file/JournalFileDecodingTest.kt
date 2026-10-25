package actron.observability.file

import actron.observability.Capability
import actron.observability.JournalEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Valid checksums must not hide malformed record fields or change the readable prefix. */
class JournalFileDecodingTest {
    @Test
    fun malformedEntryFields_stopAtTheSameRecordWithTheSameReason() {
        val malformed =
            listOf(
                "unknown entry tag 255" to record(255),
                // Envelope validation precedes dispatch by tag, even for an unknown entry.
                "needs 1 bytes, 0 left" to byteArrayOf(255.toByte()),
                "unknown input kind 255" to
                    record(2) {
                        i64(1)
                        u8(255)
                    },
                "unknown payload kind 255" to record(10) { u8(255) },
                "not a boolean: 2" to record(6) { u8(2) },
                "unknown Capability 'unknown'" to record(1) { string("unknown") },
                "1 bytes left after the payload" to record(9) { u8(0) },
                "command policy without a lane" to
                    decisionWithCommand {
                        bool(false)
                        bool(true)
                    },
                "command lane without a policy" to
                    decisionWithCommand {
                        bool(true)
                        string("net")
                        bool(false)
                    },
            )
        for ((reason, payload) in malformed) {
            assertStoppedAtRecord(payload, "record: $reason")
        }
    }

    @Test
    fun aStoreSubjectWithoutAStoreOrdinal_isStillMalformed() {
        val payload =
            ByteWriter()
                .apply {
                    u8(9)
                    bool(true)
                    string("store-a")
                    i64(2)
                    bool(false)
                    i64(0)
                }.toByteArray()
        assertStoppedAtRecord(payload, "record: record subject and Store ordinal differ")
    }

    @Test
    fun malformedHeader_keepsItsOffsetAndDoesNotReadRecords() {
        val badHeaders =
            listOf(
                "not a header" to byteArrayOf(1),
                "unknown ExecutionMode 'unknown'" to
                    ByteWriter()
                        .apply {
                            u8(0)
                            i32(1)
                            i32(7)
                            string("s1")
                            string("g")
                            string("unknown")
                            i32(0)
                        }.toByteArray(),
            )
        for ((reason, payload) in badHeaders) {
            val bytes = JournalFileFormat.MAGIC + Framing.frame(payload) + JournalWireGolden.framesV7.first().fromHex() + JournalFileFormat.END
            val decoded = JournalFileFormat.decodeSegment("header", bytes)
            assertEquals(JournalPrefix.Unreadable, decoded.prefix)
            assertEquals(emptyList(), decoded.records)
            assertEquals(SegmentMark.Corrupt("header", 8, 0, "header: $reason"), decoded.mark)
        }
    }

    private fun assertStoppedAtRecord(
        payload: ByteArray,
        reason: String,
    ) {
        val prefix = (JournalWireGolden.HEADER_V7 + JournalWireGolden.framesV7.first()).fromHex()
        val bytes = prefix + Framing.frame(payload) + JournalWireGolden.framesV7.last().fromHex() + JournalFileFormat.END
        val decoded = JournalFileFormat.decodeSegment("damaged", bytes)
        assertEquals(JournalEntry.StoreRegistered(Capability.InspectOnly), decoded.records.single().entry)
        assertEquals(SegmentMark.Corrupt("damaged", prefix.size, 1, reason), assertIs<SegmentMark.Corrupt>(decoded.mark))
        assertEquals(false, decoded.finished)
    }

    private fun record(
        tag: Int,
        body: ByteWriter.() -> Unit = {},
    ): ByteArray =
        ByteWriter()
            .apply {
                u8(tag)
                bool(true)
                string("store-a")
                i64(2)
                bool(true)
                i64(2)
                i64(0)
                body()
            }.toByteArray()

    private fun decisionWithCommand(execution: ByteWriter.() -> Unit): ByteArray =
        record(11) {
            bool(false)
            i64(1)
            repeat(4) { i32(0) }
            i32(1)
            i64(1)
            i64(1)
            execution()
        }

    private fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
