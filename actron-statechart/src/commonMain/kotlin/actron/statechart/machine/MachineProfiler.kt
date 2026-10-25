@file:OptIn(actron.core.InternalActronApi::class, actron.core.ExperimentalActronApi::class)

package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.core.InputAttribution
import actron.core.InputId
import actron.core.InternalActronApi
import actron.core.Store
import actron.core.StoreProbe
import actron.core.StoreTrace
import actron.statechart.StateId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Aggregate completed samples only. No payloads or unbounded sample lists are retained. */
data class DurationProfile(
    val samples: Long = 0,
    val total: Duration = Duration.ZERO,
    val maximum: Duration = Duration.ZERO,
) {
    val average: Duration get() = if (samples == 0L) Duration.ZERO else total / samples.toDouble()

    internal fun add(duration: Duration) = DurationProfile(samples + 1, total + duration, maxOf(maximum, duration))
}

data class TransitionProfile(
    val hits: Long = 0,
    val queue: DurationProfile = DurationProfile(),
    val processing: DurationProfile = DurationProfile(),
)

data class StateProfile(
    val visits: Long = 0,
    val residence: DurationProfile = DurationProfile(),
    val commandQueue: DurationProfile = DurationProfile(),
    val commandExecution: DurationProfile = DurationProfile(),
    val cancelledCommands: Long = 0,
)

data class MachineProfileSnapshot(
    val definition: DefinitionId,
    val version: DefinitionVersion,
    val transitions: Map<TransitionId, TransitionProfile> = emptyMap(),
    val states: Map<StateId, StateProfile> = emptyMap(),
    val droppedCorrelations: Long = 0,
)

/**
 * Opt-in, per-executor monotonic profiler. Attach [probe] to the Store, this observer to the
 * MachineStore, and wrap its command handler with [commands]. Callbacks never run user rules.
 * Input processing includes reducer/hooks/observers, not just CPU in one transition. If one
 * input takes multiple transitions, its processing duration is attributed to each, not divided.
 * Command execution measures the handler lifetime (including IO/suspension), not remote CPU.
 * Parallel states overlap, so state residence times must not be summed as wall time.
 */
class MachineProfiler<C : Any, A : Action, CMD : Any, E : Event>(
    machine: Machine<C, A, CMD, E>,
    private val capacity: Int = 500,
    private val now: () -> Duration = TimeSource.Monotonic.markNow().let { mark -> { mark.elapsedNow() } },
) : DecisionTraceObserver<C, A, CMD, E> {
    private data class InputTiming(
        val accepted: Duration,
        val started: Duration = Duration.ZERO,
        val startedKnown: Boolean = false,
        val transitions: List<TransitionId> = emptyList(),
    )

    private data class CommandTiming(
        val owner: StateId,
        val registered: Duration,
    )

    private val lock = Mutex()
    private var profile = MachineProfileSnapshot(machine.id, machine.version)
    private val inputs = linkedMapOf<InputId, InputTiming>()
    private val registrations = linkedMapOf<CommandId, CommandTiming>()
    private val active = mutableMapOf<StateId, Duration>()

    init {
        require(capacity > 0)
    }

    fun snapshot(): MachineProfileSnapshot = locked { profile }

    /** Seed a restored executor before start. Measurements begin now; past elapsed times are unknown. */
    fun beginAt(snapshot: MachineSnapshot<C>) {
        require(snapshot.definition == profile.definition && snapshot.version == profile.version)
        locked {
            check(active.isEmpty() && registrations.isEmpty() && profile.states.isEmpty() && profile.transitions.isEmpty())
            val time = now()
            for (node in snapshot.configuration.active) active[node] = time
            for ((id, command) in snapshot.commands) {
                for (owner in snapshot.activations.filterValues { it == command.scope }.keys) registrations[id] = CommandTiming(owner, time)
                trim(registrations)
            }
        }
    }

    /** Configure before start; the public helper [profileWith] hides the internal probe API. */
    internal val probe: StoreProbe<MachineSnapshot<C>, MachineInput<A>, E> =
        StoreProbe { trace ->
            locked {
                val time = now()
                when (trace) {
                    is StoreTrace.InputAccepted -> {
                        inputs[trace.input] = InputTiming(time)
                        trim(inputs)
                    }

                    is StoreTrace.ProcessingStarted -> {
                        inputs[trace.input]?.let { inputs[trace.input] = it.copy(started = time, startedKnown = true) }
                    }

                    is StoreTrace.ProcessingFinished -> {
                        inputs.remove(trace.input)?.let { timing ->
                            if (timing.startedKnown) {
                                for (id in timing.transitions.distinct()) {
                                    val previous = profile.transitions.getOrElse(id) { TransitionProfile() }
                                    profile = profile.copy(transitions = profile.transitions + (id to previous.copy(queue = previous.queue.add(timing.started - timing.accepted), processing = previous.processing.add(time - timing.started))))
                                }
                            }
                        }
                    }

                    is StoreTrace.InputDiscarded -> {
                        inputs.remove(trace.input)
                    }

                    StoreTrace.StoreClosed -> {
                        inputs.clear()
                        registrations.clear()
                        active.clear()
                    }

                    else -> {
                        Unit
                    }
                }
                Unit
            }
        }

    override fun onDecided(
        input: InputAttribution,
        machineInput: MachineInput<A>,
        explained: ExplainedDecision<C, CMD, E>,
    ) {
        // Scheduler.apply precedes onCommitted: a handler on another dispatcher can already
        // be running by then. Capture ownership before the scheduler can launch any command.
        locked {
            val time = now()
            val decision = explained.decision
            for (command in decision.commands) {
                val owners =
                    decision.snapshot.activations
                        .filterValues { it == command.scope }
                        .keys
                for (owner in owners) registrations[command.id] = CommandTiming(owner, time)
                trim(registrations)
            }
        }
    }

    override fun onCommitted(
        input: InputAttribution,
        machineInput: MachineInput<A>,
        decision: Decision<C, CMD, E>,
    ) {
        locked {
            val time = now()
            for (id in decision.transitions) {
                val previous = profile.transitions.getOrElse(id) { TransitionProfile() }
                profile = profile.copy(transitions = profile.transitions + (id to previous.copy(hits = previous.hits + 1)))
            }
            input.correlate { id -> inputs[id]?.let { inputs[id] = it.copy(transitions = it.transitions + decision.transitions) } }
            for (activation in decision.exited) {
                active.remove(activation.node)?.let { entered ->
                    state(activation.node) { it.copy(residence = it.residence.add(time - entered)) }
                }
            }
            for (activation in decision.entered) {
                active[activation.node] = time
                state(activation.node) { it.copy(visits = it.visits + 1) }
            }
            registrations.keys.retainAll(decision.snapshot.commands.keys)
        }
    }

    /** Reuses the supplied handler and sink; cancellation is observed and propagated unchanged. */
    fun commands(handler: CommandHandler<CMD, A>): CommandHandler<CMD, A> =
        CommandHandler { command, sink ->
            val started = now()
            val owner = locked { registrations.remove(command.id)?.let(::listOf).orEmpty() }
            for (registration in owner) locked { state(registration.owner) { it.copy(commandQueue = it.commandQueue.add(started - registration.registered)) } }
            var cancelled = false
            try {
                handler.execute(command, sink)
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            } finally {
                val elapsed = now() - started
                for (registration in owner) {
                    locked {
                        state(registration.owner) { it.copy(commandExecution = it.commandExecution.add(elapsed), cancelledCommands = it.cancelledCommands + if (cancelled) 1 else 0) }
                    }
                }
            }
        }

    private fun state(
        id: StateId,
        update: (StateProfile) -> StateProfile,
    ) {
        profile = profile.copy(states = profile.states + (id to update(profile.states.getOrElse(id) { StateProfile() })))
    }

    private fun <K : Any, V : Any> trim(map: MutableMap<K, V>) {
        while (map.size > capacity) {
            map.remove(map.keys.first())
            profile = profile.copy(droppedCorrelations = profile.droppedCorrelations + 1)
        }
    }

    private inline fun <T : Any> locked(block: () -> T): T {
        while (!lock.tryLock()) { /* Short metadata updates only. */ }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

/** Installs queue/processing boundaries; also register the profiler as a decision observer. */
fun <C : Any, A : Action, CMD : Any, E : Event> MachineConfiguration<C, A, E>.profileWith(profiler: MachineProfiler<C, A, CMD, E>) {
    probe(profiler.probe)
}
