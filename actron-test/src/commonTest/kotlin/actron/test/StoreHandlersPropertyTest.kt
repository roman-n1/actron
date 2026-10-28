@file:OptIn(actron.core.InternalActronApi::class)

package actron.test

import actron.core.ExceptionHandler
import actron.core.Store
import actron.core.createRuntimeStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Property-based checks for [describeHandlers]: for random definitions, each kind lists its handlers
 * in registration order with matching indices, the text rendering matches an independent renderer,
 * and the table predicts which enter, exit and recover handler the running Store picks.
 *
 * ```
 * start(s) --enter #e--> s --Fail(ex)--> recover #r (first state<..>/recover<T> matching s, ex)
 *          --Go(t), other class--> exit #x (first matching s) --> enter #e' (first matching t)
 * ```
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StoreHandlersPropertyTest {
    private fun render(regs: List<Registration>): String {
        fun name(k: kotlin.reflect.KClass<*>) = k.simpleName ?: "?"

        fun section(
            kind: String,
            line: (Int, Registration) -> String,
        ): String {
            val ofKind = regs.filter { it.kind == kind }
            return if (ofKind.isEmpty()) "$kind: none" else "$kind:\n" + ofKind.mapIndexed { i, r -> "  " + line(i, r) }.joinToString("\n")
        }
        return listOf(
            section("enter") { i, r -> "#$i state<${name(r.stateType)}>" },
            section("action") { i, r -> "#$i state<${name(r.stateType)}> / action<${name(r.inputType)}>" },
            section("exit") { i, r -> "#$i state<${name(r.stateType)}>" },
            section("recover") { i, r -> "#$i state<${name(r.stateType)}> / recover<${name(r.inputType)}>" },
        ).joinToString("\n")
    }

    @Test
    fun describesRandomDefinitionsInRegistrationOrderAndRendersExactly() {
        repeat(400) { seed ->
            val regs = randomRegistrations(Random(seed), max = 24)
            val handlers = buildKtStore(regs, KtState.B).describeHandlers()

            fun of(kind: String) = regs.filter { it.kind == kind }
            val context = "seed=$seed"

            assertEquals(of("enter").mapIndexed { i, r -> StateHandlerDescription(i, r.stateType) }, handlers.enter, context)
            assertEquals(of("exit").mapIndexed { i, r -> StateHandlerDescription(i, r.stateType) }, handlers.exit, context)
            assertEquals(of("action").mapIndexed { i, r -> ActionHandlerDescription(i, r.stateType, requireNotNull(r.inputType)) }, handlers.action, context)
            assertEquals(of("recover").mapIndexed { i, r -> RecoverHandlerDescription(i, r.stateType, requireNotNull(r.inputType)) }, handlers.recover, context)
            assertEquals(render(regs), handlers.toString(), context)
        }
    }

    @Test
    fun actionTableIsTheSameListRoutingDiagnosticsReport() {
        repeat(200) { seed ->
            val random = Random(seed)
            val store = buildKtStore(randomRegistrations(random, max = 24), KtState.B)
            assertEquals(
                store.describeHandlers().action,
                store.diagnoseActionMatches(ktStates.random(random), ktActions.random(random)).handlers,
                "seed=$seed",
            )
        }
    }

    @Test
    fun tablePredictsTheEnterExitAndRecoverHandlersTheStoreRuns() =
        runTest {
            var recovered = 0
            var moved = 0
            repeat(300) { seed ->
                val random = Random(seed)
                val regs = randomRegistrations(random, max = 24).filter { it.kind != "action" }
                val initial = ktStates.random(random)
                val target = ktStates.random(random)
                val error: Exception = listOf(IllegalStateException("s"), IllegalArgumentException("a"), KtBoom(), Exception("e")).random(random)
                val fail = random.nextBoolean()
                val log = mutableListOf<String>()
                val store =
                    buildKtStore(regs, initial, log) {
                        coroutineContext(StandardTestDispatcher(testScheduler))
                        exceptionHandler(ExceptionHandler.Ignore)
                        // One routing action at the end, so the action table stays out of the way.
                        state<KtState> {
                            action<KtAction> {
                                if (fail) throw error
                                nextState { target }
                            }
                        }
                    }
                val table = store.describeHandlers()
                val expected = ExpectedHandlerTrace(table)
                expected.enter(initial)
                if (fail) {
                    recovered += expected.recover(initial, error)
                } else if (target::class != initial::class) {
                    expected.exit(initial)
                    expected.enter(target)
                    moved++
                }

                try {
                    store.startAndAwait()
                    store.dispatchAndAwait(KtAction.P)
                    assertEquals(expected.entries, log, "seed=$seed initial=$initial target=$target fail=$fail error=$error")
                } finally {
                    store.closeAndJoin()
                }
            }
            assertTrue(recovered > 30 && moved > 60, "recovered=$recovered moved=$moved")
        }

    @Test
    fun describingIsImmediateWhileADispatchHoldsTheStore() =
        runTest {
            val storePublication = kotlinx.coroutines.CompletableDeferred<Store<KtState, KtAction, KtEvent>>()
            val insideObservations = mutableListOf<StoreHandlers>()

            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            val store: Store<KtState, KtAction, KtEvent> =
                createRuntimeStore(KtState.B) {
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    state<KtState.B> {
                        action<KtAction.P> {
                            insideObservations.add(storePublication.getCompleted().describeHandlers())
                            delay(10_000)
                        }
                        exit { }
                    }
                }
            storePublication.complete(store)

            store.dispatch(KtAction.P)
            testScheduler.advanceTimeBy(5)

            val outside = store.describeHandlers()

            assertEquals(5, currentTime)
            assertEquals(insideObservations.single().toString(), outside.toString())
            assertEquals("enter: none\naction:\n  #0 state<B> / action<P>\nexit:\n  #0 state<B>\nrecover: none", outside.toString())
            store.close()
        }

    @Test
    fun nonActronStoreIsRejected() {
        val foreign = object : Store<KtState, KtAction, KtEvent> by buildKtStore(emptyList(), KtState.B) {}
        assertFailsWith<IllegalStateException> { foreign.describeHandlers() }
    }

    @Test
    fun unnamedTypesRenderAsQuestionMarks() {
        val unnamed = object {}::class
        val handlers =
            StoreHandlers(
                enter = listOf(StateHandlerDescription(0, unnamed)),
                action = listOf(ActionHandlerDescription(0, KtState.B::class, unnamed)),
                exit = emptyList(),
                recover = listOf(RecoverHandlerDescription(0, unnamed, unnamed), RecoverHandlerDescription(1, KtState::class, KtBoom::class)),
            )
        assertEquals(
            "enter:\n  #0 state<?>\naction:\n  #0 state<B> / action<?>\nexit: none\nrecover:\n  #0 state<?> / recover<?>\n  #1 state<KtState> / recover<KtBoom>",
            handlers.toString(),
        )
    }

    @Test
    fun typesWithoutASimpleNameRenderLikeUnknownTypes() {
        // Anonymous classes have no simple name on the JVM; whatever a platform reports must be used as is.
        val anonymousState = object : actron.core.State {}::class
        val anonymousError = object : Exception() {}::class
        val name = { k: kotlin.reflect.KClass<*> -> k.simpleName ?: "?" }

        assertEquals("#0 state<${name(anonymousState)}>", StateHandlerDescription(0, anonymousState).toString())
        assertEquals(
            "#1 state<${name(anonymousState)}> / recover<${name(anonymousError)}>",
            RecoverHandlerDescription(1, anonymousState, anonymousError).toString(),
        )
    }
}

/** Independent expected trace for one generated handler table, separate from scenario execution. */
private class ExpectedHandlerTrace(
    private val table: StoreHandlers,
) {
    val entries = mutableListOf<String>()

    fun enter(state: KtState) = recordStateHandler(table.enter, state, "enter")

    fun exit(state: KtState) = recordStateHandler(table.exit, state, "exit")

    fun recover(
        state: KtState,
        error: Exception,
    ): Int {
        val selected = table.recover.filter { it.stateType.isInstance(state) && it.exceptionType.isInstance(error) }.take(1)
        entries += selected.map { "recover#${it.index}" }
        return selected.size
    }

    private fun recordStateHandler(
        handlers: List<StateHandlerDescription>,
        state: KtState,
        kind: String,
    ) {
        val index = handlers.indexOfFirst { it.stateType.isInstance(state) }
        if (index >= 0) entries += "$kind#${handlers[index].index}"
    }
}
