package actron.timetravel.inspect

import actron.observability.*
import actron.statechart.machine.MachineSnapshot
import actron.timetravel.RecordedStep
import kotlin.time.Duration

internal val Inspector.session: RuntimeSessionId get() = buildList<RuntimeSessionId> { withRun { session, _, _ -> add(session) } }.single()
internal val Inspector.group: MachineGroupId get() = buildList<MachineGroupId> { withRun { _, group, _ -> add(group) } }.single()
internal val Inspector.mode: ExecutionMode get() = buildList<ExecutionMode> { withRun { _, _, mode -> add(mode) } }.single()
internal val StoreView.capability: Capability get() = buildList<Capability> { withCapability { add(it) } }.single()
internal val StoreView.revision: Long get() = buildList<Long> { withRevision { add(it) } }.single()
internal val TimelineItem.store: StoreInstanceId get() = subject as StoreInstanceId
internal val TimelineItem.groupSeq: GroupSeq get() = (this as TimelineItem.JournalItem).groupSeq
internal val TimelineItem.elapsed: Duration get() = (this as TimelineItem.JournalItem).elapsed
internal val TimelineItem.Processing.kind: InputDescriptor<*> get() = buildList<InputDescriptor<*>> { acceptance.withKind { add(it) } }.single()
internal val TimelineItem.Discarded.kind: InputDescriptor<*> get() = buildList<InputDescriptor<*>> { acceptance.withKind { add(it) } }.single()
internal val TimelineItem.Processing.outcome: OutcomeDescriptor get() = buildList<OutcomeDescriptor> { progress.withEnd { outcome, _ -> add(outcome) } }.single()
internal val TimelineItem.Processing.duration: Duration get() = buildList<Duration> { progress.withEnd { _, duration -> add(duration) } }.single()
internal val TimelineItem.Processing.revision: Long get() = buildList<Long> { withRevision { add(it) } }.single()
internal val TimelineItem.Processing.activeNodes: List<String> get() = buildList<List<String>> { withActiveNodes { add(it) } }.single()
internal val TimelineItem.Processing.decision: JournalEntry.DecisionCommitted get() = buildList<JournalEntry.DecisionCommitted> { withDecision { add(it) } }.single()
internal val TimelineItem.Processing.ignored: String get() = buildList<String> { withIgnored { add(it) } }.single()
internal val TimelineItem.Processing.message: MessageRef get() = buildList<MessageRef> { withMessage { add(it) } }.single()
internal val TimelineItem.Processing.source: String get() = buildList<String> { withSource { add(it) } }.single()
internal val TimelineItem.Processing.recorded: RecordedStep<*, *, *, *> get() = (attachment as ProcessingAttachment.Recorded).step
internal val TimelineItem.Processing.before: MachineSnapshot<*> get() = (attachment as ProcessingAttachment.Recorded).before
internal val TimelineItem.Processing.after: MachineSnapshot<*> get() = (attachment as ProcessingAttachment.Recorded).after
internal val TimelineItem.Processing.diff: SnapshotDiff get() = (attachment as ProcessingAttachment.Recorded).diff
