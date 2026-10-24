@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.timetravel.inspect

import actron.observability.RecordSubject
import actron.observability.StoreInstanceId
import actron.observability.file.SegmentMark

/** Builds store views and ordered completeness reasons from journal evidence and attachment results. */
internal class InspectorSummary(
    private val built: InspectorJournal,
    private val timeline: List<TimelineItem>,
    private val recordingStatus: Map<StoreInstanceId, RecordingStatus>,
    private val statistics: InspectorStatistics,
) {
    val stores: List<StoreView> = built.storeIds.map { storeView(it) }
    val completeness: Completeness = Completeness(groupReasons() + stores.flatMap { it.completeness.reasons })

    private fun storeView(
        store: StoreInstanceId,
    ): StoreView {
        val items = timeline.filter { it.subject == store }
        val processings = items.filterIsInstance<TimelineItem.Processing>()
        val status = recordingStatus.getValue(store)
        val reasons = mutableListOf<Incompleteness>()
        val registrations = items.filterIsInstance<TimelineItem.Registered>()
        if (registrations.isEmpty()) reasons += Incompleteness.NotRegistered(store)
        if (status !is RecordingStatus.Attached) {
            val payloads = built.payloads(store)
            if (payloads.omitted > 0) reasons += Incompleteness.PayloadsOmitted(store, payloads.omitted)
            if (payloads.unavailable > 0) reasons += Incompleteness.PayloadsUnavailable(store, payloads.unavailable)
        }
        val pending = items.count { it is TimelineItem.Pending } + processings.count { !it.isFinished }
        if (pending > 0) reasons += Incompleteness.InputsPending(store, pending)
        val unattributed = items.count { it is TimelineItem.Unattributed }
        if (unattributed > 0) reasons += Incompleteness.Unattributed(store, unattributed)
        val undelivered = items.count { it is TimelineItem.Sent && !it.delivered }
        if (undelivered > 0) reasons += Incompleteness.MessagesUndelivered(store, undelivered)
        if (status is RecordingStatus.Mismatch) reasons += Incompleteness.RecordingMismatch(store, status.reason)
        return StoreView(
            id = store,
            registrations = registrations,
            records = built.recordCounts[store] ?: 0,
            processings = processings.size,
            revisions = buildList { for (processing in processings) processing.withRevision { add(it) } },
            closed = items.any { it is TimelineItem.Closed },
            recording = status,
            completeness = Completeness(reasons),
        )
    }

    private fun groupReasons(): List<Incompleteness> =
        buildList {
            val gaps = timeline.filterIsInstance<TimelineItem.Gap>()
            if (gaps.isNotEmpty()) add(Incompleteness.RecordsDropped(gaps.size, gaps.sumOf { it.dropped }))
            statistics.withLive { if (it.evicted > 0) add(Incompleteness.RecordsEvicted(it.evicted)) }
            statistics.withLive { if (it.publishedAfterStop > 0) add(Incompleteness.RecordsAfterStop(it.publishedAfterStop)) }
            addAll(stoppedAndDamaged())
            val unattributed = timeline.count { it is TimelineItem.Unattributed && it.subject == RecordSubject.Session }
            if (unattributed > 0) add(Incompleteness.Unattributed(RecordSubject.Session, unattributed))
        }

    private fun stoppedAndDamaged(): List<Incompleteness> =
        buildList {
            for (mark in timeline.filterIsInstance<TimelineItem.Damage>()) if (mark.mark !is SegmentMark.Unfinished) add(Incompleteness.Damaged(mark.mark))
            if (timeline.any { it is TimelineItem.Stopped }) {
                val open = built.storeIds.filter { store -> timeline.none { it is TimelineItem.Closed && it.subject == store } }
                if (open.isNotEmpty()) add(Incompleteness.StoppedWhileOpen(open))
            }
        }
}
