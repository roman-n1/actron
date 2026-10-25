@file:OptIn(ExperimentalActronApi::class)

package actron.statechart

import actron.core.Action
import actron.core.ActronStoreDsl
import actron.core.EnterLaunchScope
import actron.core.EnterScope
import actron.core.EnterTransactionScope
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.core.InternalActronApi
import actron.core.State
import actron.core.Store
import actron.core.StoreBuilder
import actron.core.StoreInternalApi
import actron.core.StoreScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * The Actron state of a Store created by [StateChartStore]: where the chart is, the data that goes
 * with it, and which timers are running.
 *
 * It is plain immutable data, so a [actron.core.StateSaver] can persist it and restore it later (see
 * [StateChartStore] for what happens on restore).
 *
 * @property configuration The active nodes and the history of the chart
 * @property context The data of the Store, which effects and hooks update (for example a retry
 * counter that a guard reads)
 * @property timers The running timers; bookkeeping of the Store (see [ChartTimers])
 */
data class ChartState<C : Any>(
    val configuration: StateConfiguration,
    val context: C,
    val timers: ChartTimers = ChartTimers(),
) : State {
    /**
     * Whether the node [id] is active: an active leaf or an ancestor of one.
     */
    fun isActive(id: StateId): Boolean = id in configuration.active

    /**
     * The active leaves of [definition] in this state, in declaration order: one per active
     * region (see [StateChartDefinition.activeLeaves]).
     */
    fun activeLeaves(definition: StateChartDefinition): List<StateId> = definition.activeLeaves(configuration)
}

/**
 * The timers a [StateChartStore] is running, with a token per running timer.
 *
 * A timer is identified by its index in [StateChartDefinition.transitions], because two
 * declarations with equal fields are equal values. Every start of a timer gets a new token, so a
 * firing that belongs to an earlier start (its source was exited, and maybe entered again, before
 * the cancellation took effect) carries a token that no longer matches and is ignored. Correctness
 * does not depend on when a cancelled coroutine stops.
 *
 * @property running The token of each running timer, by the timer's index in
 * [StateChartDefinition.transitions]
 * @property issued The last token issued; the next start gets `issued + 1`
 */
data class ChartTimers(
    val running: Map<Int, Long> = emptyMap(),
    val issued: Long = 0,
)

/**
 * Scope of an [StateChartStoreBuilder.onExit] hook, and the base of the scope of an
 * [StateChartStoreBuilder.onEnter] hook.
 */
@ActronStoreDsl
interface ChartHookScope<C : Any, E : Event> {
    /** Underlying handler scope for extensions such as `store.message(...)`. */
    val store: StoreScope

    /**
     * The node being exited or entered.
     */
    val node: StateId

    /**
     * What caused the step: the dispatched action, [TimerFired] for a timer, or [ChartInitialization] when the
     * Store enters its initial configuration on start.
     */
    val action: Action

    /**
     * The context as updated so far in this step. Assign it to update the context; the step
     * commits the final value once, together with the new configuration.
     */
    var context: C

    /**
     * Emits [event] immediately, before the step's new state is committed.
     */
    suspend fun event(event: E)
}

/**
 * Scope of an [StateChartStoreBuilder.onEnter] hook.
 */
@ActronStoreDsl
interface ChartEnterScope<C : Any, A : Action, E : Event> : ChartHookScope<C, E> {
    /**
     * Starts work that lives while [node] stays active: it is cancelled when [node] is exited (or
     * when the Store closes), and it does not start at all when the step that entered [node]
     * fails. It runs in the Store's coroutine context.
     */
    fun launch(block: suspend ChartLaunchScope<C, A, E>.() -> Unit)
}

/**
 * Scope of work that lives while [node] is active: started by [ChartEnterScope.launch] or declared
 * with [StateChartStoreBuilder.activity].
 *
 * An exception other than a cancellation is handled like one from a Actron handler: by the Store's
 * `recover {}` handlers, otherwise by its exception handler.
 */
@ActronStoreDsl
interface ChartLaunchScope<C : Any, A : Action, E : Event> {
    /** Underlying launch scope for StoreScope extensions. */
    val store: StoreScope

    /**
     * The node this work belongs to.
     */
    val node: StateId

    /**
     * Whether [node] is still in the activation this work was started for.
     */
    val isActive: Boolean

    /**
     * Emits [event] immediately if this activation is still active and the Store is open.
     * Calls from a previous activation are ignored, even if the same node has been entered again.
     */
    suspend fun event(event: E)

    /**
     * Updates the context in a Store transaction, if [node] is still active by then.
     *
     * @return Whether the update was applied
     */
    suspend fun updateContext(transform: (C) -> C): Boolean

    /**
     * Enqueues [action] while this activation is active. If the node is exited before the action
     * is processed, the action is discarded, including when the same node has been entered again.
     */
    fun dispatch(action: A)
}

/**
 * Builder of a [StateChartStore]: implementations of the chart's guard and effect labels, hooks
 * for entering and exiting nodes, and the Actron Store configuration. Registrations are frozen
 * when the Store is built; later builder edits do not change an existing Store.
 */
@ActronStoreDsl
class StateChartStoreBuilder<C : Any, A : Action, E : Event> internal constructor() {
    internal var maxMicrosteps = 100

    /** Bounds automatic transitions per input; lifecycle changes are staged until stability. */
    fun maxMicrosteps(limit: Int) {
        require(limit > 0) { "[Actron] Microstep limit must be positive" }
        maxMicrosteps = limit
    }

    internal val guards = mutableMapOf<String, (ChartState<C>, Action) -> Boolean>()
    internal val effects = mutableMapOf<String, (C, Action) -> C>()
    internal val enterHooks = mutableMapOf<StateId, MutableList<suspend ChartEnterScope<C, A, E>.() -> Unit>>()
    internal val exitHooks = mutableMapOf<StateId, MutableList<suspend ChartHookScope<C, E>.() -> Unit>>()
    internal val activities = mutableMapOf<StateId, MutableList<suspend ChartLaunchScope<C, A, E>.() -> Unit>>()
    internal val storeBlocks = mutableListOf<ChartStoreConfiguration<C, A, E>.() -> Unit>()

    /**
     * Implements the guard [label]. It receives the state before the step and the action, or
     * [TimerFired] for a timer.
     *
     * @throws IllegalArgumentException if [label] already has an implementation
     */
    fun guard(
        label: String,
        guard: (state: ChartState<C>, action: Action) -> Boolean,
    ) {
        require(label !in guards) { "[Actron] Guard '$label' is implemented twice" }
        guards[label] = guard
    }

    /**
     * Implements the effect [label]: a pure update of the context, run when a transition with this
     * effect is taken, after the exit hooks and before the enter hooks of the step. It receives
     * the action, or [TimerFired] for a timer.
     *
     * @throws IllegalArgumentException if [label] already has an implementation
     */
    fun effect(
        label: String,
        effect: (context: C, action: Action) -> C,
    ) {
        require(label !in effects) { "[Actron] Effect '$label' is implemented twice" }
        effects[label] = effect
    }

    fun guard(
        key: GuardKey,
        guard: (ChartState<C>, Action) -> Boolean,
    ) = guard(key.name, guard)

    fun effect(
        key: EffectKey,
        effect: (C, Action) -> C,
    ) = effect(key.name, effect)

    /**
     * Adds a hook run whenever [id] is entered, outermost nodes first; hooks of one node run in
     * the order added. It can update the context, emit events and [launch][ChartEnterScope.launch]
     * work that lives while [id] is active. It is not run again for a configuration restored by a
     * [actron.core.StateSaver]; use [activity] for work that must run then too.
     */
    fun onEnter(
        id: StateId,
        hook: suspend ChartEnterScope<C, A, E>.() -> Unit,
    ) {
        enterHooks.getOrPut(id) { mutableListOf() } += hook
    }

    /**
     * Adds a hook run whenever [id] is exited, innermost nodes first; hooks of one node run in the
     * order added. It can update the context and emit events. Work launched for [id] is cancelled
     * right after the step that exits it.
     */
    fun onExit(
        id: StateId,
        hook: suspend ChartHookScope<C, E>.() -> Unit,
    ) {
        exitHooks.getOrPut(id) { mutableListOf() } += hook
    }

    /**
     * Adds work that runs while [id] is active: it starts after each step that enters [id] (after
     * the step's hooks), when the Store starts in a configuration where [id] is active, restored
     * or not, and it is cancelled when [id] is exited.
     */
    fun activity(
        id: StateId,
        block: suspend ChartLaunchScope<C, A, E>.() -> Unit,
    ) {
        activities.getOrPut(id) { mutableListOf() } += block
    }

    /**
     * Configures runtime settings and context-only recovery. The chart owns the initial
     * configuration and handlers; settings cannot register competing transitions or timers.
     * Blocks run in declaration order. See [ChartStoreConfiguration.recover].
     */
    fun store(block: ChartStoreConfiguration<C, A, E>.() -> Unit) {
        storeBlocks += block
    }
}

/**
 * Creates a Actron Store that runs [definition]: a plain `Store<ChartState<C>, A, E>`, so Compose,
 * `actron-test`, plugins and state savers work as with any Store.
 *
 * The Store starts in the chart's initial configuration with [context]. Each dispatched action is
 * one [StateChartRuntime.step]; when a transition is taken, in one Actron handler and one commit:
 * the exit hooks of the exited nodes (innermost first), the effects of the transitions (in
 * selection order), the enter hooks of the entered nodes (outermost first), then work of the
 * exited nodes is cancelled, activities of the entered nodes and their timers are started. An
 * action no transition takes leaves the state unchanged and runs nothing.
 *
 * Timers ([Trigger.After]) run as coroutines in the Store: a timer starts when its source is
 * entered and fires [StateChartRuntime.fire] in a Store transaction after its delay, unless its
 * source has been exited. A firing whose guard is false changes nothing but stops the timer.
 *
 * When a hook or effect of a step throws, the step is abandoned: the chart stays in its previous
 * configuration and the error reaches the Store's `recover {}` handlers. A timer whose firing
 * failed that way is spent, so it stops; its source restarts it only when it is entered again.
 * When an enter hook fails on a fresh start, the Store still is in the initial configuration, so
 * its activities and timers start; the context changes and launches of that start's hooks are
 * dropped, and the error reaches `recover {}` once the state is committed (a dispatch queued
 * meanwhile may run first).
 *
 * On start, with the declared initial state: the enter hooks of the initial configuration run
 * (with [ChartInitialization]), then activities and timers start. With a state restored by a
 * [actron.core.StateSaver], the enter hooks do not run again (the context already reflects them),
 * but activities start and the running timers restart with their full delay. A restored
 * configuration that [definition] cannot produce, such as one naming a state that no longer
 * exists or missing a region, is replaced by the initial configuration as on a fresh start; the
 * restored context is kept.
 *
 * @param definition The chart; its hierarchy must be well formed (see [StateChartRuntime])
 * @param context The initial context
 * @param coroutineContext The Store's coroutine context, as in Actron's `Store(...)`
 * @param builder Guards, effects, hooks and Store configuration
 * @throws IllegalArgumentException if a guard or effect label used by [definition] has no
 * implementation, if a hook or activity is added for an undeclared node or a [HistoryState], if
 * a guard or effect label is implemented twice, if the hierarchy of [definition] is malformed or
 * refers to undeclared states, or if timers without a positive delay restart each other in a loop
 */
fun <C : Any, A : Action, E : Event> StateChartStore(
    definition: StateChartDefinition,
    context: C,
    coroutineContext: CoroutineContext = kotlinx.coroutines.Dispatchers.Default,
    builder: StateChartStoreBuilder<C, A, E>.() -> Unit = {},
): Store<ChartState<C>, A, E> = ChartStoreHost(definition, context, StateChartStoreBuilder<C, A, E>().apply(builder), coroutineContext).build()

/**
 * Coordinates core startup, macrosteps and failure boundaries. Bindings are frozen at build;
 * activations, queued work and timer rules have separate owners. Changes remain under the core
 * processing lock; activity/timer bodies run through the Store-lifetime subscription.
 */
@OptIn(InternalActronApi::class)
internal class ChartStoreHost<C : Any, A : Action, E : Event>(
    private val definition: StateChartDefinition,
    context: C,
    config: StateChartStoreBuilder<C, A, E>,
    private val coroutineContext: CoroutineContext,
    /** Whether a timer coroutine is cancelled with its source; tests turn it off to check the tokens alone. */
    private val cancelTimers: Boolean = true,
) {
    private val bindings = ChartStoreBindings(config)
    private val work = ChartStoreWork<C, E>()
    private val activities =
        ChartStoreActivities(bindings.activities, work::submit) { action: A, isValid: () -> Boolean ->
            @Suppress("UNCHECKED_CAST")
            (store as StoreInternalApi<ChartState<C>, A, E>).dispatchIf(action, isValid)
        }
    private val hooks = ChartStoreHooks(bindings, activities::taskFor)
    private val timerRuntime = ChartStoreTimers(definition)
    private val runtime = StateChartRuntime<ChartState<C>>(definition, { definition.activeLeaves(it.configuration).first() }, bindings.guards)

    init {
        bindings.validate(definition)
    }

    private val declaredInitial =
        ChartState(
            configuration = runtime.initialConfiguration(),
            context = context,
        ).let { it.copy(timers = timerRuntime.issue(it.timers, timerRuntime.indicesOf(definition.inEntryOrder(it.configuration.active)))) }

    /** Timer firings ignored because their token was stale; read by tests. */
    internal val staleFirings: Int get() = timerRuntime.staleFirings

    fun build(): Store<ChartState<C>, A, E> = store

    private val store: Store<ChartState<C>, A, E> by lazy {
        Store(declaredInitial, coroutineContext) {
            // The action type is erased; every action this Store receives is an A.
            @Suppress("UNCHECKED_CAST")
            (this as StoreBuilder<ChartState<C>, Action, E>).state<ChartState<C>> {
                enter { start() }
                action<Action> {
                    val result = runtime.step(state.configuration, state, action)
                    if (result is StepResult.Transitioned) {
                        val next = takeStep(state, result, action, this) { event(it) }
                        nextState { next }
                    }
                }
            }
            @Suppress("UNCHECKED_CAST")
            val settings = this as StoreBuilder<ChartState<C>, A, E>
            bindings.storeBlocks.forEach { block -> block(ChartStoreConfiguration(settings)) }
            validateRecovery { previous, recovered ->
                require(previous.configuration == recovered.configuration && previous.timers == recovered.timers) {
                    "[Actron] StateChartStore recover {} may update context only; dispatch a declared chart action to change configuration or timers"
                }
            }
            initialState(declaredInitial)
        }
    }

    private suspend fun EnterScope<ChartState<C>, E, ChartState<C>>.start() {
        subscribe { work.run(this) { task -> runTask(task) } }
        val restored = state
        val startup = ChartStoreStartup<C>(definition, declaredInitial, restored)
        val state = startup.state
        val active = startup.active
        var context = state.context
        val entered = startup.entered
        val launches = mutableListOf<ChartStoreTask<C, E>>()
        var startupResult: Result<Unit> = Result.success(Unit)
        try {
            context = enterInitial(startup, launches)
        } catch (e: Exception) {
            // The Store's own cancellation ends the start; any other exception, including an
            // expired withTimeout, is a failed hook (the core rule).
            if (e is CancellationException && !currentCoroutineContext().isActive) {
                entered.values.forEach { it.cancel() }
                throw e
            }
            // Unlike a failed action step, a failed start has no previous configuration to stay
            // in: the Store is in this one, so its activations, activities and timers must exist.
            // Only the failed hooks' context changes and launches are dropped; the error reaches
            // the recover {} handlers once the state is committed.
            startupResult = Result.failure(e)
            context = state.context
            launches.clear()
        }
        activities.initialize(entered)
        val expected = timerRuntime.indicesOf(active)
        val timers = if (state.timers.running.keys == expected.toSet()) state.timers else timerRuntime.issue(ChartTimers(issued = state.timers.issued), expected)
        var next = state.copy(context = context, timers = timers)
        if (startupResult.isSuccess) {
            try {
                next = settleStart(next)
            } catch (error: Exception) {
                startupResult = Result.failure(error)
                next = state.copy(timers = timers)
                launches.clear()
            }
        }
        launches.filter { activities.contains(it.activation) }.forEach(work::submit)
        active.filter { activities.matches(it, entered.getValue(it)) }.forEach(activities::start)
        expected.filter { next.timers.running[it] == timers.running[it] }.forEach { schedule(it, timers.running.getValue(it)) }
        if (next != restored) nextState { next }
        startupResult.onFailure { error -> launch { transaction { throw error } } }
    }

    private suspend fun EnterScope<ChartState<C>, E, ChartState<C>>.enterInitial(
        startup: ChartStoreStartup<C>,
        launches: MutableList<ChartStoreTask<C, E>>,
    ): C {
        var context = startup.state.context
        if (startup.fresh) {
            for (id in startup.active) context = hooks.enter(id, startup.entered.getValue(id), context, ChartInitialization, launches::add, this) { event(it) }
        }
        return context
    }

    private suspend fun EnterScope<ChartState<C>, E, ChartState<C>>.settleStart(state: ChartState<C>): ChartState<C> {
        val automatic = runtime.automaticStep(state.configuration, state)
        if (automatic !is StepResult.Transitioned) return state
        return takeStep(state, automatic, AutomaticTransition(automatic.transitions.first().trigger == Trigger.Completion), this) { event(it) }
    }

    /** Runs the hooks and effects of [result] and returns the state to commit. */
    private suspend fun takeStep(
        state: ChartState<C>,
        result: StepResult.Transitioned,
        action: Action,
        storeScope: StoreScope,
        emit: suspend (E) -> Unit,
    ): ChartState<C> {
        val transition = ChartStoreTransition(state, activities, timerRuntime, work, ::schedule)
        var step = result
        var stepAction = action
        val automatic = if (action is AutomaticTransition) result.transitions.toMutableList() else mutableListOf()
        var microsteps = if (action is AutomaticTransition) 1 else 0
        try {
            while (true) {
                hooks.apply(transition, step, stepAction, storeScope, emit)
                val current = transition.state
                val next = runtime.automaticStep(current.configuration, current)
                if (next !is StepResult.Transitioned) break
                if (microsteps >= bindings.maxMicrosteps) throw MicrostepLimitException(bindings.maxMicrosteps, automatic + next.transitions)
                automatic += next.transitions
                microsteps++
                step = next
                stepAction = AutomaticTransition(next.transitions.first().trigger == Trigger.Completion)
            }
        } catch (error: Throwable) {
            transition.rollback()
            throw error
        }
        return transition.commit()
    }

    private fun schedule(
        index: Int,
        token: Long,
    ) {
        val activation = if (cancelTimers) activities.activationOf(timerRuntime.sourceOf(index)) else Job()
        work.submit(
            ChartStoreTask(activation) {
                kotlinx.coroutines.delay(timerRuntime.delayOf(index))
                transactor.transaction {
                    try {
                        fire(this, index, token)
                    } catch (e: Exception) {
                        if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                        // The step failed, so the chart stays where it is and the timer is spent:
                        // drop it, or `timers.running` would list a timer that never fires again.
                        // The error reaches the Store's recover {} handlers once this has committed.
                        nextState { state.copy(timers = state.timers.copy(running = state.timers.running - index)) }
                        report(e)
                    }
                }
            },
        )
    }

    private suspend fun fire(
        scope: EnterTransactionScope<ChartState<C>, E, ChartState<C>>,
        index: Int,
        token: Long,
    ) {
        val state = scope.state
        if (!timerRuntime.accepts(state.timers, index, token)) return
        val timer = definition.transitions[index]
        val next =
            when (val result = runtime.fire(state.configuration, state, timer)) {
                is StepResult.Transitioned -> takeStep(state.copy(timers = state.timers.copy(running = state.timers.running - index)), result, TimerFired(timer), scope) { scope.event(it) }
                StepResult.Ignored -> state.copy(timers = state.timers.copy(running = state.timers.running - index))
            }
        scope.nextState { next }
    }

    private suspend fun ChartStoreWorker<C, E>.runTask(task: ChartStoreTask<C, E>) {
        val job = currentCoroutineContext().job
        val handle = task.activation.invokeOnCompletion { job.cancel() }
        try {
            task.block(this)
        } catch (e: Exception) {
            // The task's own cancellation (its node exited, the Store closed) ends it; any other
            // exception, including an expired withTimeout, is a failure of the task.
            if (e is CancellationException && !currentCoroutineContext().isActive) throw e
            report(e)
        } finally {
            handle.dispose()
        }
    }

    /**
     * Reports a failure of a task or timer step to the Store's `recover {}` handlers by rethrowing
     * it inside a transaction. The transaction is requested from the chart's own work scope, not
     * from the failed task: a transaction whose caller is cancelled is skipped, and the task's node
     * may exit (cancelling the task) before the transaction gets the lock. The transaction still
     * runs after the step that exits the node, so the handlers see the committed state.
     */
    private fun ChartStoreWorker<C, E>.report(failure: Exception) {
        scope.launch { transactor.transaction { throw failure } }
    }
}
