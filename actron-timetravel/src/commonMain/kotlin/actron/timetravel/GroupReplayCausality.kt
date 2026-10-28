package actron.timetravel

import actron.observability.StoreInstanceId
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MessageId
import actron.statechart.machine.PairRole
import actron.statechart.machine.RoutePair

/** Per-recording bridge/source causality, independent of replay cursor and machine decisions. */
internal class GroupReplayCausality(
    private val recording: GroupRecording,
) {
    private val issues = mutableListOf<GroupMismatch>()
    private val sent = recording.inFlight.toMutableSet()
    private val received = mutableMapOf<Pair<StoreInstanceId, MessageId>, Int>()

    // The requesters each member has received a request from so far, and, per message, the
    // requesters its sender had heard from when it emitted the message. A member whose
    // recording begins at a checkpoint (a run since a cut) may have heard from anyone before
    // it: it counts as having heard from every member, and a message in flight at the start
    // was emitted before it and is not checked.
    private data class Request(
        val pair: String,
        val requester: StoreInstanceId,
    )

    private val requestersHeard = mutableMapOf<StoreInstanceId, MutableSet<Request>>()
    private val heardAtEmit = mutableMapOf<MessageId, Set<Request>>()

    val mismatches: List<GroupMismatch>

    init {
        val pairs =
            recording.routes
                .map { it.pair }
                .filterIsInstance<RoutePair>()
                .map { it.name }
                .distinct()
        for ((id, member) in recording.members) {
            if (member.start.snapshot.revision > 0) {
                requestersHeard[id] = pairs.flatMap { pair -> (recording.members.keys - id).map { Request(pair, it) } }.toMutableSet()
            }
        }
        for ((index, step) in recording.order.withIndex()) {
            val recorded = recording.members.getValue(step.store).steps[step.step]
            val input = recorded.input
            if (input is MachineInput.BridgeReceived) receive(index, step.store, input.message)
            if (input is MachineInput.External && input.source !in recording.sourceIds) issues += GroupMismatch.UnknownSource(index, step.store, input.source)
            if (recorded is RecordedStep.Committed<*, *, *, *>) rememberEffects(step.store, recorded)
        }
        mismatches = issues.sortedBy { it.position }
    }

    private fun receive(
        index: Int,
        store: StoreInstanceId,
        message: MessageId,
    ) {
        val key = store to message
        val routes = recording.routes.filter { it.from == message.from && it.to == store }
        when {
            key in received -> {
                issues += GroupMismatch.DeliveredTwice(index, store, message, received.getValue(key))
            }

            message.from !in recording.members -> {
                issues += GroupMismatch.SentByNobody(index, store, message)
            }

            message !in sent -> {
                issues += GroupMismatch.ReceivedBeforeSent(index, store, message)
            }

            routes.isEmpty() -> {
                issues += GroupMismatch.NoRoute(index, store, message)
            }

            else -> {
                checkRoutes(index, store, message, routes)
            }
        }
        received[key] = index
    }

    private fun checkRoutes(
        index: Int,
        store: StoreInstanceId,
        message: MessageId,
        routes: List<GroupRoute>,
    ) {
        val replies = routes.map { it.pair }.filterIsInstance<RoutePair>().filter { it.role == PairRole.Reply }
        if (replies.isNotEmpty() && message !in recording.inFlight) {
            val reply = replies.first()
            if (Request(reply.name, store) !in heardAtEmit[message].orEmpty()) issues += GroupMismatch.ReplyWithoutRequest(index, store, message, reply.name)
        }
        for (pair in routes.map { it.pair }.filterIsInstance<RoutePair>()) {
            if (pair.role == PairRole.Request) requestersHeard.getOrPut(store) { mutableSetOf() } += Request(pair.name, message.from)
        }
    }

    private fun rememberEffects(
        store: StoreInstanceId,
        recorded: RecordedStep.Committed<*, *, *, *>,
    ) {
        val heard = requestersHeard[store].orEmpty().toSet()
        for (effect in recorded.decision.effects) {
            val message = MessageId(store, effect.id)
            sent += message
            heardAtEmit[message] = heard
        }
    }
}
