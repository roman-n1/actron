package actron.timetravel.file

import actron.core.Action
import actron.core.Event
import actron.core.InputAttribution
import actron.observability.FailureDescriptor
import actron.observability.MachineGroupId
import actron.observability.StoreInstanceId
import actron.observability.file.Framing
import actron.observability.file.SegmentOutput
import actron.observability.file.SegmentStorage
import actron.statechart.machine.CutListener
import actron.statechart.machine.Decision
import actron.statechart.machine.DecisionObserver
import actron.statechart.machine.GroupCheckpoint
import actron.statechart.machine.IgnoreReason
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineGroup
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MessageId
import actron.timetravel.GroupRoute
import actron.timetravel.Recording
import actron.timetravel.RecordingCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Records a [MachineGroup] into files as it runs: a [RecordingFileSink] per member and one
 * order file of the group, written by one writer each. [member] returns the observer to give a
 * member's store. Every order segment begins with the messages in flight before its first
 * entry, so a range that begins there checks the bridge's causality without the sends before
 * it. Every cut of the group ([MachineGroup.checkpoint]) begins a segment in every file, the
 * order segment's header carrying the cut's snapshots of the sources, so a range that begins
 * at a cut knows them; the sink registers itself as the group's [CutListener] and unregisters
 * on [close].
 *
 * @param group The group; its route history and sources are read when segments begin
 * @param id The group's name in the storage
 * @param storage Where the segments go
 * @param scope Runs the writers
 * @param config Budgets of every file
 */
class GroupRecordingFileSink(
    private val group: MachineGroup,
    private val id: MachineGroupId,
    private val storage: SegmentStorage,
    private val scope: CoroutineScope,
    private val config: RecordingFileConfig = RecordingFileConfig(),
) {
    private val lock = Mutex()
    private val sinks = linkedMapOf<StoreInstanceId, RecordingFileSink<*, *, *, *>>()
    private val counts = mutableMapOf<StoreInstanceId, Int>()
    private val pending = mutableMapOf<MessageId, Int>()
    private var nextEntry = 0
    private var beginSegment = true
    private var droppedEntries = 0L
    private var droppedCutCount = 0L
    private var closed = false

    private val cutListener = CutListener { cut(it) }

    init {
        group.onCut(cutListener)
    }

    private val queue = Channel<GroupOrderItem>(config.queueCapacity)

    private val segments = GroupOrderSegmentWriter(storage, id, config, ::headerFor, ::finishOutput, ::closeOutput)
    private var failed = false

    private val writer: Job =
        scope.launch {
            var completion: Result<Unit> = Result.success(Unit)
            try {
                for (item in queue) {
                    if (failed) continue
                    try {
                        segments.write(item)
                    } catch (e: Exception) {
                        if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                        failed = true
                        segments.release(Result.failure(e))
                        notifyFailure(e)
                    }
                }
                if (!failed) segments.finish()
            } catch (t: Throwable) {
                completion = Result.failure(t)
                if (t is CancellationException || t !is Exception) throw t
                notifyFailure(t)
            } finally {
                // Cancellation leaves an unfinished segment; never append END to a failed frame.
                try {
                    segments.release(completion)
                } finally {
                    locked { closed = true }
                    queue.cancel()
                }
            }
        }

    private fun notifyFailure(failure: Throwable) {
        try {
            config.onFailure(failure)
        } catch (_: Exception) {
            // Reporting a storage failure must not fail the writer's parent or its cleanup.
        }
    }

    private fun closeOutput(
        output: SegmentOutput,
        completion: Result<Unit>,
    ) {
        try {
            output.close()
        } catch (t: Throwable) {
            completion.fold(
                onSuccess = { throw t },
                onFailure = { primary -> if (primary !== t) primary.addSuppressed(t) },
            )
        }
    }

    /** The members' sinks, by id. */
    val members: Map<StoreInstanceId, RecordingFileSink<*, *, *, *>> get() = locked { sinks.toMap() }

    /** Order entries the writer had no room for. */
    val dropped: Long get() = locked { droppedEntries }

    /** Cuts the writer had no room for: the files then have no boundary there, nothing else is lost. */
    val droppedCuts: Long get() = locked { droppedCutCount }

    /**
     * The observer for the store of [memberId]: its steps go to the member's own recording file
     * and to the group's order.
     */
    fun <C : Any, A : Action, CMD : Any, E : Event> member(
        memberId: StoreInstanceId,
        machine: Machine<C, A, CMD, E>,
        context: C,
        codec: RecordingCodec<C, A, CMD, E>,
    ): DecisionObserver<C, A, CMD, E> {
        val sink =
            locked {
                check(!closed) { "[Actron] This group sink is closed" }
                require(memberId !in sinks) { "[Actron] $memberId is already recorded by this group sink" }
                RecordingFileSink(memberId, machine, context, codec, storage, scope, config).also { sinks[memberId] = it }
            }
        return object : DecisionObserver<C, A, CMD, E> {
            override fun onCommitted(
                input: InputAttribution,
                machineInput: MachineInput<A>,
                decision: Decision<C, CMD, E>,
            ) {
                sink.onCommitted(input, machineInput, decision)
                val routes = group.routes.filter { it.from == memberId }
                val sent =
                    buildList {
                        for (effect in decision.effects) {
                            for (route in routes) {
                                route.mapEvent(effect.event) {
                                    add(MessageId(memberId, effect.id) to route.to)
                                }
                            }
                        }
                    }
                order(memberId, if (machineInput is MachineInput.BridgeReceived) GroupDelivery.Bridged(machineInput.message) else GroupDelivery.Local, sent)
            }

            override fun onIgnored(
                input: InputAttribution,
                machineInput: MachineInput<A>,
                reason: IgnoreReason,
            ) {
                sink.onIgnored(input, machineInput, reason)
                order(memberId, if (machineInput is MachineInput.BridgeReceived) GroupDelivery.Bridged(machineInput.message) else GroupDelivery.Local, emptyList())
            }

            override fun onFailed(
                input: InputAttribution,
                machineInput: MachineInput<A>,
                failure: FailureDescriptor,
            ) {
                sink.onFailed(input, machineInput, failure)
                order(memberId, if (machineInput is MachineInput.BridgeReceived) GroupDelivery.Bridged(machineInput.message) else GroupDelivery.Local, emptyList())
            }
        }
    }

    /** Finishes every file with its end frame and stops the writers. */
    suspend fun close() {
        val members =
            locked {
                closed = true
                sinks.values.toList()
            }
        group.removeCutListener(cutListener)
        queue.close()
        writer.join()
        for (sink in members) sink.close()
    }

    private fun order(
        memberId: StoreInstanceId,
        delivery: GroupDelivery,
        sent: List<Pair<MessageId, StoreInstanceId>>,
    ) {
        val item =
            locked {
                if (closed) return
                val step = counts[memberId] ?: 0
                counts[memberId] = step + 1
                delivery.withReceived { message ->
                    if (message in pending) {
                        val count = pending.getValue(message)
                        if (count <= 1) pending.remove(message) else pending[message] = count - 1
                    }
                }
                val inFlight = pending.keys.toList()
                for ((message, _) in sent) pending[message] = (pending[message] ?: 0) + 1
                GroupOrderItem.Order(GroupOrderEntry(memberId, step, sent.map { it.first }.distinct(), delivery), nextEntry++, inFlight, beginSegment).also { beginSegment = false }
            }
        if (queue.trySend(item).isFailure) {
            locked {
                droppedEntries++
                beginSegment = true
            }
        }
    }

    // Inside the group's cut: the members are frozen and idle, so the counts are exactly the cut's.
    private fun cut(checkpoint: GroupCheckpoint) {
        val item =
            locked {
                if (closed) return
                val members = sinks.keys.associateWith { counts[it] ?: 0 }
                for (sink in sinks.values) sink.cut()
                beginSegment = false
                GroupOrderItem.Cut(RecordedCut(nextEntry, members, checkpoint.sources), pending.keys.toList())
            }
        if (queue.trySend(item).isFailure) locked { droppedCutCount++ }
    }

    private fun headerFor(
        item: GroupOrderItem,
        index: Int,
    ): GroupSegmentHeader = GroupSegmentHeader(GroupRecordingFileFormat.VERSION, id, locked { sinks.keys.toList() }, group.routeHistory.map { GroupRoute(it.from, it.to, it.pair) }, group.sourceIds.toSet(), index, item.index, item.inFlight, item.boundary)

    private fun finishOutput(output: SegmentOutput) {
        var completion: Result<Unit> = Result.success(Unit)
        try {
            output.write(Framing.END)
        } catch (t: Throwable) {
            completion = Result.failure(t)
            throw t
        } finally {
            closeOutput(output, completion)
        }
    }

    private inline fun <T : Any> locked(block: () -> T): T {
        while (!lock.tryLock()) {
            // Spin: the holder assigns one entry.
        }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

/**
 * Reads what a [GroupRecordingFileSink] wrote: the order file's last continuous range and every
 * member's, cut to the range they all cover. The members' recordings are trimmed to their
 * checkpoint at that range's first entry; the messages in flight there come from the order
 * segment's header and the entries before it. The cuts inside the range are offered too: a
 * range since a cut knows the sources' snapshots.
 */
class GroupRecordingFiles(
    private val storage: SegmentStorage,
) {
    /** The groups that have order files. */
    fun groups(): List<MachineGroupId> = buildList { for (info in storage.list()) GroupRecordingFileFormat.parseSegmentName(info.name) { id, _ -> add(id) } }.distinct()

    fun read(
        group: MachineGroupId,
        codecs: Map<StoreInstanceId, RecordingCodec<*, *, *, *>>,
    ): GroupRecordingFileContents {
        val marks = mutableListOf<RecordingFileMark>()
        val runs = GroupOrderHistory(storage).read(group, marks)
        if (runs.isEmpty()) return GroupRecordingFileContents.Unreadable(marks.toList())
        val order = runs.last()

        val members = mutableMapOf<StoreInstanceId, Pair<Recording<*, *, *, *>, Int>>()
        for (member in order.header.members) {
            if (member !in codecs) {
                marks += RecordingFileMark.StartMismatch(member, "-", "no codec for $member")
                continue
            }
            val contents = storage.readMember(member, codecs.getValue(member))
            marks += contents.marks
            if (contents !is RecordingFileContents.Readable) continue
            members[member] = contents.recording to contents.firstStep
        }
        return GroupRecordingRange(order, members, marks).read()
    }

    @Suppress("UNCHECKED_CAST")
    private fun SegmentStorage.readMember(
        member: StoreInstanceId,
        codec: RecordingCodec<*, *, *, *>,
    ): RecordingFileContents<*, *, *, *> = RecordingFiles(this).read(member, codec as RecordingCodec<Any, Action, Any, Event>)
}
