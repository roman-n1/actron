@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.timetravel.compose

import actron.observability.ExecutionMode
import actron.observability.GroupSeq
import actron.observability.JOURNAL_FORMAT_VERSION
import actron.observability.JournalEntry
import actron.observability.JournalRecord
import actron.observability.MachineGroupId
import actron.observability.RecordOrdinal
import actron.observability.RecordSubject
import actron.observability.RuntimeSessionId
import actron.timetravel.inspect.Inspector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class InspectorSelectionRetentionTest {
    @Test
    fun liveRefreshKeepsSelectedEventWhenItsPositionShifts() {
        fun record(seq: Long): JournalRecord<Nothing, Nothing, Nothing> =
            JournalRecord(
                JOURNAL_FORMAT_VERSION,
                RuntimeSessionId("run"),
                MachineGroupId("group"),
                RecordSubject.Session,
                ExecutionMode.Live,
                GroupSeq(seq),
                RecordOrdinal.Session,
                seq.milliseconds,
                JournalEntry.JournalGap(seq),
            )
        val records = (1L..4L).map(::record)
        val state = InspectorState(Inspector.of(records.take(3)), InspectorMode.Live)
        state.select(1)
        var before = 0L
        state.withSelected { it.withPosition { seq, _ -> before = seq.value } }
        assertEquals(2L, before)
        state.refresh(Inspector.of(records.drop(1)))
        var after = 0L
        state.withSelected { it.withPosition { seq, _ -> after = seq.value } }
        assertEquals(2L, after, "Selected event must stay GroupSeq(2), which still exists in the retained window")
        state.refresh(Inspector.of(records.drop(2)))
        assertEquals(InspectorSelection.Overview, state.selection, "Evicted events clear selection")
        state.select(0)
        val anotherRun = records.drop(2).map { it.copy(session = RuntimeSessionId("another-run")) }
        state.refresh(Inspector.of(anotherRun))
        assertEquals(InspectorSelection.Overview, state.selection, "Equal sequence numbers in another run are different events")
    }
}
