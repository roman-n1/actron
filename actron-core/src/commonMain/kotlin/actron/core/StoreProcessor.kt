package actron.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@OptIn(InternalActronApi::class)
internal class StoreProcessor<S : State, A : Action, E : Event>(
    val configuration: StoreConfiguration<S, A, E>,
    handlers: StoreHandlers<S, A, E>,
    val handlerRegistry: HandlerRegistry<S, A, E>,
) {
    private val reporting = StoreReporting(configuration)

    val state: StateFlow<S> get() = publication.state
    val event: Flow<E> get() = publication.event
    val currentState: S get() = publication.currentState

    val execution =
        StoreExecution(
            context = { configuration.coroutineContext },
            contextUsed = { configuration.contextUsed() },
            onFailure = reporting::handleException,
            onClosed = { reporting.trace { StoreTrace.StoreClosed } },
        )

    private val pluginScope by lazy {
        pluginRunner.scope(
            rootScope = { execution.scope },
            dispatchAction = { launchDispatch(it) },
            readState = { currentState },
            origin = { inputs.current.launchOrigin() },
        )
    }

    private val mutex = Mutex()

    private val pluginRunner by lazy { StorePlugins(configuration.plugins) }

    // Construction only creates adapters; the root coroutine scope is resolved on state entry.
    private val stateScopes =
        StoreStateScopes<S, A, E>(
            execution =
                object : StoreScopeExecution<S, E> {
                    override suspend fun emit(event: E) {
                        publication.emit(currentState, event)
                    }

                    override fun clearPendingActions() {
                        dispatchQueue.clear(DiscardReason.ClearedExplicitly)
                    }

                    override fun <LS : Any> launch(
                        stateRuntime: StateRuntime,
                        dispatcher: CoroutineContext,
                        buildLaunchScope: suspend () -> LS,
                        block: suspend LS.() -> Unit,
                    ): Job = launchedWork.launch(stateRuntime, dispatcher, buildLaunchScope, block)

                    override suspend fun transaction(
                        dispatcher: CoroutineContext,
                        canRun: () -> Boolean,
                        block: suspend (S) -> S,
                    ) {
                        launchedWork.transaction(dispatcher, canRun, block)
                    }
                },
            rootScope = { execution.scope },
            cleanup = { block -> execution.scope.launch { mutex.withLock { block() } } },
        )

    // Inputs accepted and not finished or discarded, kept next to the traces that say so: what
    // awaitIdle waits for before it joins the launches.
    private val inputs = StoreInputs<S, A, E>(hasProbes = { configuration.probes.isNotEmpty() }, record = reporting::record)

    private val publication: StorePublication<S, A, E> =
        StorePublication(
            configuration = configuration,
            inputs = inputs,
            execution = execution,
            reporting = reporting,
            onStatePublished = { previous, next -> processPlugins { onState(pluginScope, previous, next) } },
            onEventEmitted = { state, event -> processPlugins { onEvent(pluginScope, state, event) } },
        )

    private val dispatchQueue =
        StoreDispatchQueue(
            rootScope = { execution.scope },
            mutex = mutex,
            discard = inputs::discard,
        )

    private val startup: StoreStartup<S, A, E> =
        StoreStartup(
            configuration = { configuration },
            inputs = inputs,
            startPlugins = { processPlugins(starting = true) { onStart(pluginScope, currentState) } },
            enter = { transitions.enter(currentState) },
            report = { reporting.reportWithoutAborting(it, inputs.current.attribution) },
        )

    val idleWaiter =
        StoreIdleWaiter(
            inputs = inputs,
            mutex = mutex,
            activeLaunches = stateScopes::activeLaunches,
            closingJobs = execution::closingJobs,
            checkOutside = execution::checkOutside,
        )

    private val transitions: StoreTransitions<S, A, E> =
        StoreTransitions(
            handlers = handlers,
            stateScopes = stateScopes,
            inputs = inputs,
            effects =
                object : StoreTransitionEffects<S, A> {
                    override val currentState: S get() = this@StoreProcessor.currentState

                    override suspend fun beforeAction(
                        state: S,
                        action: A,
                    ) {
                        // The builder selects the first match; this extra check only attributes ignored inputs.
                        if (configuration.probes.isNotEmpty() && !handlerRegistry.hasAction(state, action)) {
                            inputs.current.ignore()
                        }
                        processPlugins { onAction(pluginScope, state, action) }
                    }

                    override suspend fun publishState(
                        state: S,
                        nextState: S,
                    ) {
                        publication.commit(state, nextState)
                    }

                    override fun clearOnStateExit() {
                        if (configuration.pendingActionPolicy == PendingActionPolicy.ClearOnStateExit && startup.isInitialized) {
                            dispatchQueue.clear(DiscardReason.ClearedOnStateExit)
                        }
                    }
                },
        )

    // Passive wiring: creating the executor does not consume state/context or start a Job.
    private val launchedWork =
        StoreLaunchedWork(
            execution = execution,
            inputs = inputs,
            mutex = mutex,
            transitions = transitions,
            readState = { currentState },
        )

    fun applyPatch(patch: StorePatch<S, A, E>) {
        check(mutex.tryLock()) { "[Actron] Failed to configure the Store because it is starting or already started" }
        try {
            configuration.applyPatch(patch)
        } finally {
            mutex.unlock()
        }
    }

    fun launchStartup(): Job {
        val input = startup.request()
        return execution.scope.launch(execution.inside + InputOrigin(input)) {
            mutex.withLock {
                startup.initialize()
            }
        }
    }

    fun launchDispatch(
        action: A,
        isValid: () -> Boolean = { true },
    ): Job {
        // The startup this dispatch may trigger gets its id before the action.
        startup.request()
        val input = inputs.accept(InputKind.Dispatch(action))
        return dispatchQueue.enqueue(input, execution.inside + InputOrigin(input)) {
            processDispatch(input, action, isValid)
        }
    }

    // Must be called only while holding `mutex`.
    private suspend fun processDispatch(
        input: InputId,
        action: A,
        isValid: () -> Boolean,
    ) {
        try {
            startup.initialize()
        } catch (t: Throwable) {
            // The startup this dispatch triggered failed, so the action is dropped; the next
            // dispatch or start() retries the startup (see StoreStartup.initialize).
            val closed = t is CancellationException && !currentCoroutineContext().isActive
            inputs.discard(input, if (closed) DiscardReason.StoreClosed else DiscardReason.StartupFailed(t.boundaryCause()))
            throw t
        }
        val valid =
            try {
                isValid()
            } catch (t: Throwable) {
                inputs.discard(input, DiscardReason.Rejected(t))
                throw t
            }
        if (!valid) {
            inputs.discard(input, DiscardReason.Stale)
            return
        }
        inputs.process(input) { transitions.dispatch(currentState, action) }
    }

    private suspend fun processPlugins(
        starting: Boolean = false,
        block: suspend Plugin<S, A, E>.() -> Unit,
    ) {
        try {
            pluginRunner.process(configuration.pluginExecutionPolicy, starting, block)
        } catch (t: Throwable) {
            rethrowIfNonRecoverable(t)
            throw StoreBoundaryFailure(t)
        }
    }
}

// The input a coroutine works for: carried by the dispatch, startup, transaction and recovery
// coroutines and by the coroutines a handler or a plugin launches. Read by [currentInputId].
internal class InputOrigin(
    val input: InputId,
) : AbstractCoroutineContextElement(InputOrigin) {
    companion object Key : CoroutineContext.Key<InputOrigin>
}
