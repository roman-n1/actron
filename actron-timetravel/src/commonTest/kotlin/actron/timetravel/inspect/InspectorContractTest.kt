@file:OptIn(actron.core.ExperimentalActronApi::class, actron.core.InternalActronApi::class)

package actron.timetravel.inspect

import actron.core.InputId
import actron.observability.Capability
import actron.observability.ExecutionMode
import actron.observability.GroupSeq
import actron.observability.InputDescriptor
import actron.observability.JOURNAL_FORMAT_VERSION
import actron.observability.JournalEntry
import actron.observability.JournalRecord
import actron.observability.JournalStats
import actron.observability.MachineGroupId
import actron.observability.MessageRef
import actron.observability.OutcomeDescriptor
import actron.observability.OutcomeKind
import actron.observability.Payload
import actron.observability.RecordOrdinal
import actron.observability.RecordSubject
import actron.observability.RuntimeSessionId
import actron.observability.StoreInstanceId
import actron.observability.StoreSeq
import actron.observability.file.JournalFileEvent
import actron.observability.file.SegmentMark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds

class InspectorContractTest {
    private val store = StoreInstanceId("member")

    private fun event(
        seq: Long,
        entry: JournalEntry<Nothing, Nothing, Nothing>,
        subject: RecordSubject = store,
    ): JournalFileEvent.Record =
        JournalFileEvent.Record(
            JournalRecord(JOURNAL_FORMAT_VERSION, RuntimeSessionId("run"), MachineGroupId("group"), subject, ExecutionMode.Live, GroupSeq(seq), if (subject is StoreInstanceId) StoreSeq(seq) else RecordOrdinal.Session, seq.milliseconds, entry),
        )

    @Test
    fun damageKeepsItsReadPositionWhenPendingInputsAreInsertedAndLateEventsAreAttached() {
        val firstDamage = SegmentMark.Corrupt("one", 12, 1, "checksum")
        val lastDamage = SegmentMark.Unfinished("two", 3)
        val inspector =
            Inspector(
                listOf(
                    event(1, JournalEntry.StoreRegistered(Capability.InspectOnly)),
                    event(2, JournalEntry.InputAccepted(InputId(1), InputDescriptor.Startup)),
                    JournalFileEvent.Mark(firstDamage),
                    event(3, JournalEntry.InputAccepted(InputId(2), InputDescriptor.Startup)),
                    event(4, JournalEntry.ProcessingStarted(InputId(2), 1)),
                    event(5, JournalEntry.ProcessingFinished(InputId(2), 1, OutcomeDescriptor(OutcomeKind.Handled, 0), 1.milliseconds)),
                    JournalFileEvent.Mark(lastDamage),
                    event(6, JournalEntry.InputAccepted(InputId(3), InputDescriptor.Startup)),
                    event(7, JournalEntry.EventEmitted(InputId(2), Payload.Projected("late"))),
                ),
            )
        val items = inspector.timeline
        assertEquals(6, items.size)
        assertIs<TimelineItem.Registered>(items[0])
        assertSame(firstDamage, assertIs<TimelineItem.Damage>(items[1]).mark)
        assertEquals(InputId(1), assertIs<TimelineItem.Pending>(items[2]).input)
        val processing = assertIs<TimelineItem.Processing>(items[3])
        assertEquals(listOf(Payload.Projected("late")), processing.events)
        assertEquals(InputId(2), processing.input)
        assertSame(lastDamage, assertIs<TimelineItem.Damage>(items[4]).mark)
        assertEquals(InputId(3), assertIs<TimelineItem.Pending>(items[5]).input)
        assertEquals(7, inspector.stores.single().records)
    }

    @Test
    fun equalInputIdsOfDifferentStoresNeverShareAcceptanceOrAttachments() {
        val other = StoreInstanceId("other")
        val inspector =
            Inspector(
                listOf(
                    event(1, JournalEntry.InputAccepted(InputId(1), InputDescriptor.Startup), other),
                    event(2, JournalEntry.InputAccepted(InputId(1), InputDescriptor.Dispatch(Payload.Projected("tap")))),
                    event(3, JournalEntry.ProcessingStarted(InputId(1), 1)),
                    event(4, JournalEntry.ProcessingStarted(InputId(1), 1), other),
                    event(5, JournalEntry.EventEmitted(InputId(1), Payload.Projected("other-event")), other),
                    event(6, JournalEntry.EventEmitted(InputId(1), Payload.Projected("member-event"))),
                ),
            )
        assertEquals(listOf(other, store), inspector.stores.map { it.id })
        val first = assertIs<TimelineItem.Processing>(inspector.timelineOf(store).single())
        val second = assertIs<TimelineItem.Processing>(inspector.timelineOf(other).single())
        assertEquals(InputDescriptor.Dispatch(Payload.Projected("tap")), assertIs<InputTrace.Accepted>(first.acceptance).kind)
        assertEquals(InputDescriptor.Startup, assertIs<InputTrace.Accepted>(second.acceptance).kind)
        assertEquals(listOf(Payload.Projected("member-event")), first.events)
        assertEquals(listOf(Payload.Projected("other-event")), second.events)
    }

    @Test
    fun completenessRetainsReasonOrderAndCountsOrphanPayloadsBeforeAttribution() {
        val corruption = SegmentMark.Corrupt("damaged", 10, 0, "checksum")
        val events =
            listOf(
                event(1, JournalEntry.StoreRegistered(Capability.InspectOnly)),
                event(2, JournalEntry.InputAccepted(InputId(1), InputDescriptor.Dispatch(Payload.Omitted))),
                event(3, JournalEntry.ProcessingStarted(InputId(1), 1)),
                event(4, JournalEntry.StateCommitted(InputId(1), 1, Payload.Omitted, Payload.Unavailable)),
                event(5, JournalEntry.EventEmitted(InputId(9), Payload.Omitted), RecordSubject.Session),
                event(6, JournalEntry.EventEmitted(InputId(9), Payload.Unavailable)),
                event(7, JournalEntry.BridgeSent(InputId(1), MessageRef(store, 1), StoreInstanceId("absent"), false)),
                event(8, JournalEntry.JournalGap(4), RecordSubject.Session),
                JournalFileEvent.Mark(SegmentMark.Unfinished("tail", 1)),
                JournalFileEvent.Mark(corruption),
                event(9, JournalEntry.RecordingStopped, RecordSubject.Session),
            )
        val inspector = Inspector(events, emptyMap(), JournalStats(9, 9, 2, 4, 0, 0, 3))
        assertEquals(
            listOf(
                Incompleteness.RecordsDropped(1, 4),
                Incompleteness.RecordsEvicted(2),
                Incompleteness.RecordsAfterStop(3),
                Incompleteness.Damaged(corruption),
                Incompleteness.StoppedWhileOpen(listOf(store)),
                Incompleteness.Unattributed(RecordSubject.Session, 1),
                Incompleteness.PayloadsOmitted(store, 2),
                Incompleteness.PayloadsUnavailable(store, 2),
                Incompleteness.InputsPending(store, 1),
                Incompleteness.Unattributed(store, 1),
                Incompleteness.MessagesUndelivered(store, 1),
            ),
            inspector.completeness.reasons,
        )
        assertEquals(
            inspector.completeness.reasons.takeLast(5),
            inspector.stores
                .single()
                .completeness.reasons,
        )
    }

    @Test
    fun anInputAcceptanceWithSessionSubjectStillFailsItsStoreInvariant() {
        val error =
            assertFailsWith<IllegalStateException> {
                Inspector(listOf(event(1, JournalEntry.InputAccepted(InputId(1), InputDescriptor.Startup), RecordSubject.Session)))
            }
        assertEquals("Check failed.", error.message)
    }
}
