@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.observability

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/** The boundaries between retention, queue admission, failure callbacks and writer lifetime. */
class RecordingSessionLifecycleTest {
    private val store = StoreInstanceId("a")

    @Test
    fun closeRetainsItsFinalGapAndStopBeforeWaitingForAFullQueue() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val delivered = mutableListOf<JournalRecord<*, *, *>>()
            val session =
                RecordingSession(
                    backgroundScope,
                    config = JournalConfig(retainedRecords = 3, writerQueueCapacity = 1),
                    sinks =
                        listOf(
                            JournalSink {
                                if (it.groupSeq.value == 1L) {
                                    entered.complete(Unit)
                                    release.await()
                                }
                            },
                            JournalSink { delivered += it },
                        ),
                )
            session.publish(store, JournalEntry.StoreClosed)
            entered.await()
            repeat(3) { session.publish(store, JournalEntry.StoreClosed) }
            val beforeClose = session.records()
            assertEquals(listOf(3L, 4L, 5L), beforeClose.sequences())
            assertEquals(3L, session.stats.droppedForSinks)

            val closing = launch { session.close() }
            runCurrent()

            assertFalse(closing.isCompleted, "close waits for the writer's full queue")
            assertEquals(listOf(5L, 6L, 7L), session.records().sequences())
            assertEquals(listOf(JournalEntry.StoreClosed, JournalEntry.JournalGap(3), JournalEntry.RecordingStopped), session.records().map { it.entry })
            assertEquals(GroupSeq(7), session.boundary)
            session.publish(store, JournalEntry.StoreClosed)
            assertEquals(JournalStats(7, 3, 4, 3, 0, 0, 1), session.stats)

            release.complete(Unit)
            closing.join()
            session.close()

            assertEquals(listOf(1L, 2L, 6L, 7L), delivered.sequences())
            assertEquals(JournalEntry.JournalGap(3), delivered[2].entry)
            assertEquals(RecordSubject.Session, delivered[2].store)
            assertEquals(RecordOrdinal.Session, delivered[2].storeSeq)
            assertEquals(listOf(3L, 4L, 5L), beforeClose.sequences(), "records() returned a copy")
            assertEquals(JournalStats(7, 3, 4, 3, 0, 0, 1), session.stats, "repeated close allocates nothing")
        }

    @Test
    fun closeWaitsForTheLastSinkToFinishRecordingStopped() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val session =
                RecordingSession(
                    backgroundScope,
                    sinks =
                        listOf(
                            JournalSink {
                                entered.complete(Unit)
                                release.await()
                            },
                        ),
                )
            val closing = launch { session.close() }
            entered.await()

            assertFalse(closing.isCompleted, "sending RecordingStopped is not the end of delivery")
            assertEquals(listOf(JournalEntry.RecordingStopped), session.records().map { it.entry })
            release.complete(Unit)
            closing.join()
        }

    @Test
    fun failureHandlerMayReadStatsAndPublishBeforeTheNextSinkRuns() =
        runTest {
            val calls = mutableListOf<String>()
            val failure = IllegalStateException("sink")
            var report: (JournalFailure) -> Unit = {}
            val broken =
                JournalSink {
                    calls += "first:${it.groupSeq.value}"
                    if (it.groupSeq.value == 1L) throw failure
                }
            val session =
                RecordingSession(
                    backgroundScope,
                    config = JournalConfig(onFailure = { report(it) }),
                    sinks = listOf(broken, JournalSink { calls += "second:${it.groupSeq.value}" }),
                )
            report = {
                val reported = kotlin.test.assertIs<JournalFailure.SinkFailed>(it)
                assertSame(failure, reported.error)
                assertSame(broken, reported.sink)
                assertEquals(1L, session.stats.sinkFailures)
                calls += "failure"
                session.publish(store, JournalEntry.StoreClosed)
            }

            session.publish(store, JournalEntry.StoreClosed)
            runCurrent()
            session.close()

            assertEquals(0L, session.stats.publishedAfterStop)
            assertEquals(listOf("first:1", "failure", "second:1", "first:2", "second:2", "first:3", "second:3"), calls)
            assertEquals(listOf(JournalEntry.StoreClosed, JournalEntry.StoreClosed, JournalEntry.RecordingStopped), session.records().map { it.entry })
        }

    @Test
    fun aSinkCancellationExceptionDoesNotCancelAnActiveWriter() =
        runTest {
            val failure = CancellationException("sink operation")
            val failures = mutableListOf<JournalFailure>()
            val delivered = mutableListOf<Long>()
            val session =
                RecordingSession(
                    backgroundScope,
                    config = JournalConfig(onFailure = { failures += it }),
                    sinks =
                        listOf(
                            JournalSink { if (it.groupSeq.value == 1L) throw failure },
                            JournalSink { delivered += it.groupSeq.value },
                        ),
                )
            repeat(2) { session.publish(store, JournalEntry.StoreClosed) }

            session.close()

            assertEquals(listOf(1L, 2L, 3L), delivered)
            assertEquals(1L, session.stats.sinkFailures)
            assertSame(failure, kotlin.test.assertIs<JournalFailure.SinkFailed>(failures.single()).error)
        }

    @Test
    fun cancellingTheOwnerStopsTheWriterWithoutReportingASinkFailure() =
        runTest {
            val owner = SupervisorJob()
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + owner)
            val entered = CompletableDeferred<Unit>()
            val cleaned = CompletableDeferred<Unit>()
            val failures = mutableListOf<JournalFailure>()
            val delivered = mutableListOf<Long>()
            val session =
                RecordingSession(
                    scope,
                    config = JournalConfig(onFailure = { failures += it }),
                    sinks =
                        listOf(
                            JournalSink {
                                entered.complete(Unit)
                                try {
                                    awaitCancellation()
                                } finally {
                                    cleaned.complete(Unit)
                                }
                            },
                            JournalSink { delivered += it.groupSeq.value },
                        ),
                )
            try {
                repeat(2) { session.publish(store, JournalEntry.StoreClosed) }
                entered.await()
                owner.cancelAndJoin()

                assertTrue(cleaned.isCompleted)
                assertEquals(emptyList(), delivered)
                assertEquals(emptyList(), failures)
                assertEquals(0L, session.stats.sinkFailures)
                assertEquals(listOf(1L, 2L), session.records().sequences())
            } finally {
                owner.cancelAndJoin()
            }
        }

    @Test
    fun retentionDoesNotResetSequenceNumbersOrTheSessionClock() =
        runTest {
            val clock = TestTimeSource()
            val delivered = mutableListOf<JournalRecord<*, *, *>>()
            val id = RuntimeSessionId("session")
            val group = MachineGroupId("group")
            val session =
                RecordingSession(
                    backgroundScope,
                    id = id,
                    group = group,
                    mode = ExecutionMode.Replay,
                    config = JournalConfig(retainedRecords = 1),
                    sinks = listOf(JournalSink { delivered += it }),
                    timeSource = clock,
                )
            assertEquals(JournalBoundary.Beginning, session.boundary)
            clock += 5.milliseconds
            session.publish(store, JournalEntry.StoreClosed)
            val first = session.records()
            clock += 7.milliseconds
            session.publish(StoreInstanceId("b"), JournalEntry.StoreClosed)
            session.publish(store, JournalEntry.StoreClosed)
            session.close()

            assertEquals(listOf(1L), first.sequences())
            assertEquals(listOf(1L, 2L, 3L, 4L), delivered.sequences())
            assertEquals(listOf(StoreSeq(1), StoreSeq(1), StoreSeq(2), RecordOrdinal.Session), delivered.map { it.storeSeq })
            assertEquals(listOf(5, 12, 12, 12).map { it.milliseconds }, delivered.map { it.elapsed })
            assertTrue(delivered.all { it.session == id && it.group == group && it.mode == ExecutionMode.Replay })
            assertEquals(listOf(delivered.last()), session.records())
            assertEquals(JournalStats(4, 1, 3, 0, 0, 0, 0), session.stats)
            assertEquals(GroupSeq(4), session.boundary)
        }

    private fun List<JournalRecord<*, *, *>>.sequences(): List<Long> = map { it.groupSeq.value }
}
