@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.timetravel.inspect

import actron.observability.OutcomeKind
import actron.observability.StoreInstanceId
import actron.timetravel.RecordedStep
import actron.timetravel.Recording

/** Attaches a recording only after every aligned decision agrees; failed matches publish no attachments. */
internal class InspectorRecordings(
    journal: InspectorJournal,
    recordings: Map<StoreInstanceId, Recording<*, *, *, *>>,
) {
    val timeline: List<TimelineItem>
    val statuses: Map<StoreInstanceId, RecordingStatus>

    init {
        val enriched = mutableMapOf<StoreInstanceId, RecordingStatus>()
        var timeline = journal.items
        for (store in journal.storeIds) {
            if (store !in recordings) {
                enriched[store] = RecordingStatus.Unrecorded
                continue
            }
            val recording = recordings.getValue(store)
            when (val result = enrich(timeline, store, recording)) {
                is Enrichment.Done -> {
                    timeline = result.timeline
                    enriched[store] = RecordingStatus.Attached(recording.length)
                }

                is Enrichment.Mismatch -> {
                    enriched[store] = RecordingStatus.Mismatch(result.reason)
                }
            }
        }
        for ((store, _) in recordings) if (store !in journal.storeIds) enriched[store] = RecordingStatus.Mismatch("the journal has no records of $store")
        statuses = enriched
        this.timeline = timeline
    }

    private sealed interface Enrichment {
        class Done(
            val timeline: List<TimelineItem>,
        ) : Enrichment

        class Mismatch(
            val reason: String,
        ) : Enrichment
    }

    private fun enrich(
        timeline: List<TimelineItem>,
        store: StoreInstanceId,
        recording: Recording<*, *, *, *>,
    ): Enrichment {
        val decisions = timeline.withIndex().filter { (_, item) -> item is TimelineItem.Processing && item.store == store && item.isDecision }
        // A recording that begins at a checkpoint covers the decisions after the one that committed its first revision.
        val startRevision = recording.initial.revision
        val from =
            if (startRevision == 0L) {
                0
            } else {
                decisions.indexOfFirst { (_, item) -> (item as TimelineItem.Processing).hasRevision(startRevision) }.let {
                    if (it < 0) return Enrichment.Mismatch("the recording begins at revision $startRevision, which the journal does not show for $store")
                    it + 1
                }
            }
        val aligned = decisions.drop(from)
        if (aligned.size < recording.length) return Enrichment.Mismatch("the recording has ${recording.length} steps, the journal ${aligned.size} decisions of $store after revision $startRevision")
        val enriched = timeline.toMutableList()
        for ((step, pair) in recording.steps.zip(aligned).withIndex()) {
            val (recorded, indexed) = pair
            val index = indexed.index
            val item = indexed.value as TimelineItem.Processing
            checkStep(recorded, item, step) { return Enrichment.Mismatch(it) }
            val before = recording.snapshotAt(step)
            val after = recording.snapshotAt(step + 1)
            enriched[index] = item.copy(attachment = ProcessingAttachment.Recorded(recorded, before, after))
        }
        return Enrichment.Done(enriched)
    }

    private inline fun checkStep(
        recorded: RecordedStep<*, *, *, *>,
        item: TimelineItem.Processing,
        step: Int,
        mismatch: (String) -> Nothing,
    ) {
        when (recorded) {
            is RecordedStep.Committed -> {
                val revision = recorded.decision.snapshot.revision
                if (!item.hasRevision(revision)) mismatch("step $step: recorded revision $revision, the journal has ${item.revisionLabel("no commit")} for input ${item.input}")
            }

            is RecordedStep.Ignored -> {
                var reason = ""
                val ignored = item.withIgnored { reason = it }
                if (ignored && reason != recorded.reason.name) mismatch("step $step: recorded as ignored (${recorded.reason}), the journal says $reason")
                if (!ignored && item.commits.isNotEmpty()) mismatch("step $step: recorded as ignored, the journal has a commit for input ${item.input}")
            }

            is RecordedStep.Failed -> {
                var agrees = true
                var actual = ""
                item.progress.withEnd { outcome, _ ->
                    actual = outcome.kind.toString()
                    agrees = outcome.kind == OutcomeKind.Failed || outcome.kind == OutcomeKind.Recovered
                }
                if (!agrees) mismatch("step $step: recorded as failed, the journal says $actual for input ${item.input}")
            }
        }
    }
}
