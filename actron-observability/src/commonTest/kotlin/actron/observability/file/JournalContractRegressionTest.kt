@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.observability.file

import actron.observability.ExecutionMode
import actron.observability.GroupSeq
import actron.observability.JOURNAL_FORMAT_VERSION
import actron.observability.JournalConfig
import actron.observability.JournalEntry
import actron.observability.JournalRecord
import actron.observability.JournalSink
import actron.observability.MachineGroupId
import actron.observability.RecordingSession
import actron.observability.RuntimeSessionId
import actron.observability.StoreInstanceId
import actron.observability.StoreSeq
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

class JournalContractRegressionTest {
    private val store = StoreInstanceId("store")

    @Test
    fun concurrentCloseMustDeliverTheFinalStop() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val delivered = mutableListOf<JournalEntry<*, *, *>>()
            val session =
                RecordingSession(
                    backgroundScope,
                    config = JournalConfig(writerQueueCapacity = 1),
                    sinks =
                        listOf(
                            JournalSink {
                                if (it.groupSeq.value == 1L) {
                                    entered.complete(Unit)
                                    release.await()
                                }
                                delivered += it.entry
                            },
                        ),
                )
            session.publish(store, JournalEntry.StoreClosed)
            entered.await()
            repeat(2) { session.publish(store, JournalEntry.StoreClosed) }
            val first = async { runCatching { session.close() } }
            runCurrent()
            val second = async { runCatching { session.close() } }
            runCurrent()
            release.complete(Unit)
            val outcomes = listOf(first.await(), second.await())
            assertTrue(outcomes.all { it.isSuccess }, "close results: $outcomes, delivered: $delivered")
            assertEquals(listOf<JournalEntry<*, *, *>>(JournalEntry.StoreClosed, JournalEntry.StoreClosed, JournalEntry.JournalGap(1), JournalEntry.RecordingStopped), delivered)
            assertEquals(5L, session.stats.published)
            assertEquals(1L, session.stats.droppedForSinks)
        }

    @Test
    fun retryAfterCancelledCloseMustFinishItsFinalRecords() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val delivered = mutableListOf<JournalEntry<*, *, *>>()
            val session =
                RecordingSession(
                    backgroundScope,
                    config = JournalConfig(writerQueueCapacity = 1),
                    sinks =
                        listOf(
                            JournalSink {
                                if (it.groupSeq.value == 1L) {
                                    entered.complete(Unit)
                                    release.await()
                                }
                                delivered += it.entry
                            },
                        ),
                )
            session.publish(store, JournalEntry.StoreClosed)
            entered.await()
            repeat(2) { session.publish(store, JournalEntry.StoreClosed) }
            val first = launch { session.close() }
            runCurrent()
            first.cancelAndJoin()
            release.complete(Unit)
            session.close()
            assertEquals(listOf<JournalEntry<*, *, *>>(JournalEntry.StoreClosed, JournalEntry.StoreClosed, JournalEntry.JournalGap(1), JournalEntry.RecordingStopped), delivered)
            assertEquals(5L, session.stats.published)
            assertEquals(1L, session.stats.droppedForSinks)
        }

    @Test
    fun invalidHeaderIdentityMustBecomeACorruptionMark() {
        val header =
            ByteWriter()
                .apply {
                    u8(0)
                    i32(1)
                    i32(7)
                    string("")
                    string("group")
                    string("Live")
                    i32(0)
                }.toByteArray()
        val decoded = JournalFileFormat.decodeSegment("invalid", JournalFileFormat.MAGIC + Framing.frame(header) + JournalFileFormat.END)
        val mark = assertIs<SegmentMark.Corrupt>(decoded.mark)
        assertEquals(8, mark.offset)
        assertEquals(0, mark.recordsRead)
        assertEquals(JournalPrefix.Unreadable, decoded.prefix)
    }

    @Test
    fun invalidRecordIdentityMustPreserveTheReadablePrefix() {
        val first = record(1, ExecutionMode.Live)
        val prefix = JournalFileFormat.header(first.session, first.group, first.mode, 0) + JournalFileFormat.frame(first)
        val badRecord =
            ByteWriter()
                .apply {
                    u8(9)
                    bool(true)
                    string("")
                    i64(2)
                    bool(true)
                    i64(2)
                    i64(0)
                }.toByteArray()
        val decoded = JournalFileFormat.decodeSegment("invalid-record", prefix + Framing.frame(badRecord) + JournalFileFormat.END)
        assertEquals(listOf(first), decoded.records)
        val mark = assertIs<SegmentMark.Corrupt>(decoded.mark)
        assertEquals(prefix.size, mark.offset)
        assertEquals(1, mark.recordsRead)
    }

    @Test
    fun sinkMustRefuseMixedModes() =
        runTest {
            val storage = InMemorySegmentStorage()
            val sink = JournalFileSink(storage)
            try {
                sink.write(record(1, ExecutionMode.Live))
                assertFailsWith<IllegalArgumentException> { sink.write(record(2, ExecutionMode.Replay)) }
                sink.write(record(3, ExecutionMode.Live))
            } finally {
                sink.close()
            }
            assertEquals(listOf(record(1, ExecutionMode.Live), record(3, ExecutionMode.Live)), JournalFiles(storage).read(RuntimeSessionId("session")).records)
        }

    @Test
    fun reopeningTheSameSessionMustRespectRetention() =
        runTest {
            val storage = InMemorySegmentStorage()
            repeat(2) {
                val sink = JournalFileSink(storage, JournalFileConfig(maxSegments = 1))
                sink.write(record(it + 1L, ExecutionMode.Live))
                sink.close()
            }
            assertEquals(1, storage.list().size, storage.list().map { it.name }.toString())
            val contents = JournalFiles(storage).read(RuntimeSessionId("session"))
            assertEquals(listOf(record(2, ExecutionMode.Live)), contents.records)
            assertEquals(listOf(SegmentMark.MissingSegments(RuntimeSessionId("session"), 0, 0)), contents.marks)
        }

    private fun record(
        seq: Long,
        mode: ExecutionMode,
    ) = JournalRecord(
        JOURNAL_FORMAT_VERSION,
        RuntimeSessionId("session"),
        MachineGroupId("group"),
        store,
        mode,
        GroupSeq(seq),
        StoreSeq(seq),
        Duration.ZERO,
        JournalEntry.StoreClosed,
    )
}
