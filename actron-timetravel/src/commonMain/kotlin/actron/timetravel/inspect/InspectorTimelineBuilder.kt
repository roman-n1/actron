package actron.timetravel.inspect

import actron.core.InputAttribution
import actron.core.InputId
import actron.observability.GroupSeq
import actron.observability.InputDescriptor
import actron.observability.JournalEntry
import actron.observability.JournalRecord
import actron.observability.Payload
import actron.observability.RecordSubject
import actron.observability.StoreInstanceId
import actron.observability.file.JournalFileEvent
import kotlin.time.Duration

internal class InspectorPayloadCounts(
    var omitted: Int = 0,
    var unavailable: Int = 0,
)

internal class InspectorJournal(
    val items: List<TimelineItem>,
    val storeIds: List<StoreInstanceId>,
    val recordCounts: Map<StoreInstanceId, Int>,
    private val payloadCounts: Map<StoreInstanceId, InspectorPayloadCounts>,
) {
    fun payloads(store: StoreInstanceId): InspectorPayloadCounts = payloadCounts[store] ?: InspectorPayloadCounts()
}

internal class InspectorTimelineBuilder(
    private val events: List<JournalFileEvent>,
) {
    private sealed interface TimelineSlot {
        fun build(): TimelineItem
    }

    private class FinishedSlot(
        private val item: TimelineItem,
    ) : TimelineSlot {
        override fun build(): TimelineItem = item
    }

    private class ProcessingBuilder(
        val groupSeq: GroupSeq,
        val elapsed: Duration,
        val store: StoreInstanceId,
        val input: InputId,
        val acceptance: InputTrace,
        val ordinal: Long,
    ) : TimelineSlot {
        var progress: ProcessingProgress = ProcessingProgress.Unfinished
        val commits = mutableListOf<TimelineItem.Commit>()
        val events = mutableListOf<Payload<*>>()
        val failures = mutableListOf<actron.observability.FailureDescriptor>()
        val decisions = mutableListOf<DecisionEvidence>()
        val provenance = mutableListOf<InputProvenance>()

        override fun build(): TimelineItem.Processing =
            TimelineItem.Processing(
                groupSeq,
                elapsed,
                store,
                input,
                acceptance,
                acceptance.cause,
                ordinal,
                progress,
                commits.toList(),
                events.toList(),
                failures.toList(),
                decisions.toList(),
                ProcessingAttachment.JournalOnly,
                provenance.toList(),
            )
    }

    private class Accepted(
        val groupSeq: GroupSeq,
        val elapsed: Duration,
        val kind: InputDescriptor<*>,
    ) {
        var consumed = false
    }

    // Slots keep journal positions while processings collect late records. Acceptances are
    // tracked separately and become Pending items unless a processing or discard consumes them.
    private val slots = mutableListOf<TimelineSlot>()
    private val processings = mutableMapOf<Pair<StoreInstanceId, InputId>, ProcessingBuilder>()
    private val accepted = mutableMapOf<Pair<StoreInstanceId, InputId>, Accepted>()
    private val storeIds = mutableListOf<StoreInstanceId>()
    private val recordCounts = mutableMapOf<StoreInstanceId, Int>()
    private val payloadCounts = mutableMapOf<StoreInstanceId, InspectorPayloadCounts>()

    fun build(): InspectorJournal {
        for (event in events) {
            when (event) {
                is JournalFileEvent.Mark -> slots += FinishedSlot(TimelineItem.Damage(event.mark))
                is JournalFileEvent.Record -> record(event.record)
            }
        }
        val items = slots.map { it.build() }
        // Acceptances nobody consumed are inputs without an end, at the position they were accepted.
        val pending = accepted.filterValues { !it.consumed }.map { (key, acceptance) -> TimelineItem.Pending(acceptance.groupSeq, acceptance.elapsed, key.first, key.second, acceptance.kind) }
        val ordered =
            (items + pending).sortedWith { a: TimelineItem, b: TimelineItem ->
                when {
                    a is TimelineItem.JournalItem && b is TimelineItem.JournalItem -> a.groupSeq.value.compareTo(b.groupSeq.value)
                    a is TimelineItem.JournalItem -> -1
                    b is TimelineItem.JournalItem -> 1
                    else -> 0
                }
            }
        return InspectorJournal(stableDamage(items, pending, ordered), storeIds.toList(), recordCounts.toMap(), payloadCounts.toMap())
    }

    // Sorting by sequence would move damage marks (no sequence) to the end; keep them where they were read.
    private fun stableDamage(
        items: List<TimelineItem>,
        pending: List<TimelineItem.Pending>,
        ordered: List<TimelineItem>,
    ): List<TimelineItem> {
        if (pending.isEmpty()) return items
        if (items.none { it is TimelineItem.Damage }) return ordered
        val result = mutableListOf<TimelineItem>()
        val pendingBySeq = pending.sortedBy { it.groupSeq.value }.toMutableList()
        for (item in items) {
            item.withPosition { seq, _ ->
                while (pendingBySeq.isNotEmpty() && pendingBySeq.first().groupSeq.value < seq.value) result += pendingBySeq.removeFirst()
            }
            result += item
        }
        result += pendingBySeq
        return result
    }

    private fun record(record: JournalRecord<*, *, *>) {
        val subject = record.store
        if (subject is StoreInstanceId) {
            if (subject !in storeIds) storeIds += subject
            recordCounts[subject] = (recordCounts[subject] ?: 0) + 1
        }

        when (record.entry) {
            is JournalEntry.InputAccepted<*>, is JournalEntry.InputDiscarded, is JournalEntry.ProcessingStarted -> recordInput(record)

            is JournalEntry.StateCommitted<*>, is JournalEntry.EventEmitted<*>, is JournalEntry.FailureReported,
            is JournalEntry.ProcessingFinished, is JournalEntry.DecisionCommitted, is JournalEntry.DecisionIgnored,
            is JournalEntry.BridgeReceived, is JournalEntry.ExternalReceived,
            -> recordProcessing(record)

            is JournalEntry.EffectQueued<*>, is JournalEntry.EffectHandlingStarted,
            is JournalEntry.EffectAcknowledged, is JournalEntry.EffectDiscarded,
            -> recordEffect(record)

            is JournalEntry.StoreRegistered, JournalEntry.StoreClosed, is JournalEntry.InputRejected<*>,
            is JournalEntry.BridgeSent, is JournalEntry.BridgeDropped, is JournalEntry.CheckpointCreated,
            is JournalEntry.CommandsAbandoned, is JournalEntry.JournalGap, JournalEntry.RecordingStopped,
            -> recordPosition(record)
        }
    }

    private fun recordInput(record: JournalRecord<*, *, *>) {
        val subject = record.store

        fun member(): StoreInstanceId {
            check(subject is StoreInstanceId)
            return subject
        }
        val seq = record.groupSeq
        val elapsed = record.elapsed
        when (val entry = record.entry) {
            is JournalEntry.InputAccepted<*> -> {
                count(subject, entry.kind)
                val acceptance = Accepted(seq, elapsed, entry.kind)
                accepted[member() to entry.input] = acceptance
            }

            is JournalEntry.InputDiscarded -> {
                val store = member()
                val acceptance = consume(store to entry.input)
                slots += FinishedSlot(TimelineItem.Discarded(seq, elapsed, store, entry.input, acceptance, entry.reason))
            }

            is JournalEntry.ProcessingStarted -> {
                val store = member()
                val acceptance = consume(store to entry.input)
                val processing = ProcessingBuilder(seq, elapsed, store, entry.input, acceptance, entry.ordinal)
                processings[store to entry.input] = processing
                slots += processing
            }

            else -> {
                error("[Actron] Unexpected inspector record: $entry")
            }
        }
    }

    private fun recordProcessing(record: JournalRecord<*, *, *>) {
        val subject = record.store
        when (val entry = record.entry) {
            is JournalEntry.StateCommitted<*> -> {
                count(subject, entry.previous)
                count(subject, entry.state)
                attach(subject, entry.input, record) { commits += TimelineItem.Commit(entry.revision, entry.previous, entry.state) }
            }

            is JournalEntry.EventEmitted<*> -> {
                count(subject, entry.event)
                attach(subject, entry.input, record) { events += entry.event }
            }

            is JournalEntry.FailureReported -> {
                attach(subject, entry.input, record) { failures += entry.failure }
            }

            is JournalEntry.ProcessingFinished -> {
                attach(subject, entry.input, record) {
                    progress = ProcessingProgress.Finished(entry.outcome, entry.duration)
                }
            }

            is JournalEntry.DecisionCommitted -> {
                for (command in entry.commands) count(subject, command.command)
                attach(subject, entry.input, record) { decisions += DecisionEvidence.Committed(entry) }
            }

            is JournalEntry.DecisionIgnored -> {
                attach(subject, entry.input, record) { decisions += DecisionEvidence.Ignored(entry.reason) }
            }

            is JournalEntry.BridgeReceived -> {
                attach(subject, entry.input, record) { provenance += InputProvenance.Bridge(entry.message) }
            }

            is JournalEntry.ExternalReceived -> {
                attach(subject, entry.input, record) { provenance += InputProvenance.External(entry.source) }
            }

            else -> {
                error("[Actron] Unexpected inspector record: $entry")
            }
        }
    }

    private fun recordPosition(record: JournalRecord<*, *, *>) {
        val subject = record.store

        fun member(): StoreInstanceId {
            check(subject is StoreInstanceId)
            return subject
        }
        val seq = record.groupSeq
        val elapsed = record.elapsed
        when (val entry = record.entry) {
            is JournalEntry.StoreRegistered -> {
                slots += FinishedSlot(TimelineItem.Registered(seq, elapsed, member(), entry.capability))
            }

            JournalEntry.StoreClosed -> {
                slots += FinishedSlot(TimelineItem.Closed(seq, elapsed, member()))
            }

            is JournalEntry.InputRejected<*> -> {
                count(subject, entry.action)
                slots += FinishedSlot(TimelineItem.Rejected(seq, elapsed, member(), entry.action, entry.reason))
            }

            is JournalEntry.BridgeSent -> {
                slots += FinishedSlot(TimelineItem.Sent(seq, elapsed, member(), entry.input, entry.message, entry.to, entry.delivered, entry.cause))
            }

            is JournalEntry.BridgeDropped -> {
                slots += FinishedSlot(TimelineItem.Dropped(seq, elapsed, member(), entry.message, entry.reason))
            }

            is JournalEntry.CheckpointCreated -> {
                slots += FinishedSlot(TimelineItem.Checkpoint(seq, elapsed, entry.members, entry.sources, entry.inFlight))
            }

            is JournalEntry.CommandsAbandoned -> {
                slots += FinishedSlot(TimelineItem.Abandoned(seq, elapsed, member(), entry.reason, entry.queued, entry.running))
            }

            is JournalEntry.JournalGap -> {
                slots += FinishedSlot(TimelineItem.Gap(seq, elapsed, entry.dropped))
            }

            JournalEntry.RecordingStopped -> {
                slots += FinishedSlot(TimelineItem.Stopped(seq, elapsed))
            }

            else -> {
                error("[Actron] Unexpected inspector record: $entry")
            }
        }
    }

    private fun recordEffect(record: JournalRecord<*, *, *>) {
        val subject = record.store

        fun member(): StoreInstanceId {
            check(subject is StoreInstanceId)
            return subject
        }
        val seq = record.groupSeq
        val elapsed = record.elapsed
        when (val entry = record.entry) {
            is JournalEntry.EffectQueued<*> -> {
                count(subject, entry.event)
                slots += FinishedSlot(TimelineItem.Effect(seq, elapsed, member(), entry.effect, entry))
            }

            is JournalEntry.EffectHandlingStarted -> {
                slots += FinishedSlot(TimelineItem.Effect(seq, elapsed, member(), entry.effect, entry))
            }

            is JournalEntry.EffectAcknowledged -> {
                slots += FinishedSlot(TimelineItem.Effect(seq, elapsed, member(), entry.effect, entry))
            }

            is JournalEntry.EffectDiscarded -> {
                slots += FinishedSlot(TimelineItem.Effect(seq, elapsed, member(), entry.effect, entry))
            }

            else -> {
                error("[Actron] Unexpected inspector effect: $entry")
            }
        }
    }

    private fun consume(key: Pair<StoreInstanceId, InputId>): InputTrace {
        if (key !in accepted) return InputTrace.UnrecordedAcceptance
        val acceptance = accepted.getValue(key)
        acceptance.consumed = true
        return InputTrace.Accepted(acceptance.groupSeq, acceptance.elapsed, acceptance.kind)
    }

    private inline fun attach(
        subject: RecordSubject,
        input: InputAttribution,
        record: JournalRecord<*, *, *>,
        update: ProcessingBuilder.() -> Unit,
    ) {
        if (subject is StoreInstanceId && input is InputId) {
            val key = subject to input
            if (key in processings) {
                processings.getValue(key).update()
                return
            }
        }
        slots += FinishedSlot(TimelineItem.Unattributed(record.groupSeq, record.elapsed, subject, record.entry))
    }

    private fun count(
        subject: RecordSubject,
        kind: InputDescriptor<*>,
    ) {
        when (kind) {
            is InputDescriptor.Dispatch -> count(subject, kind.action)
            else -> Unit
        }
    }

    private fun count(
        subject: RecordSubject,
        payload: Payload<*>,
    ) {
        if (subject !is StoreInstanceId) return
        val counts = payloadCounts.getOrPut(subject) { InspectorPayloadCounts() }
        when (payload) {
            Payload.Omitted -> counts.omitted++
            Payload.Unavailable -> counts.unavailable++
            else -> Unit
        }
    }
}
