package actron.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/** Owns plugin hook serialization and the successful startup registrations of one Store. */
internal class StorePlugins<S : State, A : Action, E : Event>(
    private val plugins: List<Plugin<S, A, E>>,
) {
    // Events may arrive from launches outside the Store's transition lock.
    private val mutex = Mutex()
    private val startedPluginIndices = mutableSetOf<Int>()

    fun scope(
        rootScope: () -> CoroutineScope,
        dispatchAction: (A) -> Unit,
        readState: () -> S,
        origin: () -> CoroutineContext,
    ): PluginScope<S, A> =
        object : PluginScope<S, A> {
            override fun dispatch(action: A) {
                dispatchAction(action)
            }

            override fun launch(
                dispatcher: CoroutineContext,
                block: suspend PluginLaunchScope<S, A>.() -> Unit,
            ) {
                rootScope().launch(dispatcher + origin()) {
                    block(
                        object : PluginLaunchScope<S, A> {
                            override val currentState: S get() = readState()

                            override fun dispatch(action: A) {
                                dispatchAction(action)
                            }
                        },
                    )
                }
            }
        }

    suspend fun process(
        policy: PluginExecutionPolicy,
        starting: Boolean,
        block: suspend Plugin<S, A, E>.() -> Unit,
    ) {
        if (plugins.isEmpty()) return
        mutex.withLock {
            val round = plugins.withIndex().filter { !starting || it.index !in startedPluginIndices }
            val outcomes = execute(round, policy, block)
            if (starting) {
                round.forEachIndexed { i, plugin ->
                    if (outcomes[i].isSuccess) startedPluginIndices += plugin.index
                }
            }
            throwFailures(outcomes)
        }
    }

    private suspend fun execute(
        round: List<IndexedValue<Plugin<S, A, E>>>,
        policy: PluginExecutionPolicy,
        block: suspend Plugin<S, A, E>.() -> Unit,
    ): List<Result<Unit>> =
        if (policy == PluginExecutionPolicy.InRegistrationOrder || round.size == 1) {
            round.map { (_, plugin) -> hookFailure { plugin.block() } }
        } else {
            coroutineScope {
                round.map { (_, plugin) -> async { hookFailure { plugin.block() } } }.awaitAll()
            }
        }

    private fun throwFailures(outcomes: List<Result<Unit>>) {
        // Every plugin sees the round; a failure must not hide it from later plugins.
        val failures = buildList { outcomes.forEach { result -> result.onFailure { add(it) } } }
        if (failures.isEmpty()) return
        // Fatal errors retain priority even if an ordinary exception came first.
        val fatal = failures.filter { it !is Exception }
        val first = if (fatal.isNotEmpty()) fatal.first() else failures.first()
        failures.forEach { if (it !== first) first.addSuppressed(it) }
        throw first
    }

    // Coroutine cancellation propagates; an exception thrown by an active hook is an outcome.
    private suspend inline fun hookFailure(block: () -> Unit): Result<Unit> =
        try {
            block()
            Result.success(Unit)
        } catch (t: Throwable) {
            if (t is CancellationException && !currentCoroutineContext().isActive) throw t
            Result.failure(t)
        }
}
