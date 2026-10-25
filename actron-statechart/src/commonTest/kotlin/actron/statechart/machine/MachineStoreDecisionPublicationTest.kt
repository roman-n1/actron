@file:OptIn(actron.core.InternalActronApi::class, actron.core.ExperimentalActronApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.core.ExceptionHandler
import actron.core.InputAttribution
import actron.core.Plugin
import actron.core.PluginScope
import actron.core.StoreProbe
import actron.core.StoreTrace
import actron.core.closeAndJoin
import actron.observability.FailureDescriptor
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.test.dispatchAndAwait
import actron.test.patch
import actron.test.startAndAwait
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MachineStoreDecisionPublicationTest {
    private sealed interface Act : Action {
        data object Publish : Act

        data object Ignore : Act

        data object Fail : Act
    }

    private enum class Notice : Event { First, Second, RolledBack }

    private class Fixture(
        scope: TestScope,
        val timeline: MutableList<String> = mutableListOf(),
        observers: List<DecisionObserver<Int, Act, Nothing, Notice>> = emptyList(),
    ) {
        val failure = IllegalArgumentException("failed decision")
        val errors = mutableListOf<Throwable>()
        var reductions = 0
        private val node = StateId("active")
        private val machine =
            Machine<Int, Act, Nothing, Notice>(
                DefinitionId("publication"),
                DefinitionVersion("1"),
                StateChartDefinition(node, listOf(AtomicState(node)), emptyList()),
            ) {
                onAction(node, ActionMatcher.of<Act.Publish>("publish")) {
                    reductions++
                    context++
                    event(Notice.First)
                    event(Notice.Second)
                }
                onAction(node, ActionMatcher.of<Act.Fail>("fail")) {
                    context = 999
                    event(Notice.RolledBack)
                    throw failure
                }
            }
        val store =
            MachineStore(
                machine,
                0,
                CommandHandler<Nothing, Act> { _, _ -> },
                scope.backgroundScope,
                clock =
                    object : MachineClock {
                        override fun now(): MachineTime = MachineTime.Zero

                        override suspend fun delayUntil(deadline: MachineTime) = awaitCancellation()
                    },
                coroutineContext = StandardTestDispatcher(scope.testScheduler),
                observers = observers,
                mailbox = MailboxConfig(policy = { EffectPolicy.Retained() }),
            ) {
                exceptionHandler(ExceptionHandler { errors += it })
                probe(
                    StoreProbe { trace ->
                        if (trace is StoreTrace.StateCommitted) timeline += "commit:${trace.state.context}"
                    },
                )
                plugin(Plugin(onState = { _, state -> timeline += "state:${state.context}" }))
            }
    }

    private fun eventPlugin(consume: suspend (Int, Notice) -> Unit): Plugin<MachineSnapshot<Int>, Act, Notice> =
        object : Plugin<MachineSnapshot<Int>, Act, Notice> {
            override suspend fun onEvent(
                scope: PluginScope<MachineSnapshot<Int>, Act>,
                state: MachineSnapshot<Int>,
                event: Notice,
            ) = consume(state.context, event)
        }

    @Test
    fun diagnosticsDecideOnceAndPublicationFollowsCommitThenObserversThenEffectMajorPluginOrder() =
        runTest {
            val timeline = mutableListOf<String>()
            val ids = mutableListOf<InputAttribution>()
            val observer =
                object : DecisionTraceObserver<Int, Act, Nothing, Notice>, DecisionExplanationObserver {
                    override fun onExplained(
                        input: InputAttribution,
                        explanation: DecisionExplanation,
                    ) {
                        ids += input
                        timeline += "explained"
                    }

                    override fun onDecided(
                        input: InputAttribution,
                        machineInput: MachineInput<Act>,
                        explained: ExplainedDecision<Int, Nothing, Notice>,
                    ) {
                        ids += input
                        timeline += "decided"
                    }

                    override fun onCommitted(
                        input: InputAttribution,
                        machineInput: MachineInput<Act>,
                        decision: Decision<Int, Nothing, Notice>,
                    ) {
                        ids += input
                        timeline += "observer:${decision.snapshot.context}"
                    }
                }
            val f = Fixture(this, timeline, listOf(observer))
            f.store.patch { plugin(eventPlugin { context, event -> timeline += "a:$context:$event" }, eventPlugin { context, event -> timeline += "b:$context:$event" }) }
            try {
                f.store.startAndAwait()
                timeline.clear()
                ids.clear()
                f.store.dispatchAndAwait(Act.Publish)
                assertEquals(listOf("explained", "decided", "commit:1", "state:1", "observer:1", "a:1:First", "b:1:First", "a:1:Second", "b:1:Second"), timeline)
                assertEquals(1, f.reductions, "diagnostics must reuse the actual decision")
                assertEquals(3, ids.size)
                assertEquals(1, ids.distinct().size, "diagnostics and publication retain the same input attribution")
                runCurrent()
                assertEquals(
                    listOf(Notice.First, Notice.Second),
                    f.store.mailbox.pending
                        .map { it.event },
                )
                assertTrue(f.errors.isEmpty())
            } finally {
                f.store.closeAndJoin()
            }
        }

    @Test
    fun ignoredAndFailedDecisionsDoNotPublishOrLeakEffectsIntoTheNextCommit() =
        runTest {
            val outcomes = mutableListOf<String>()
            val observer =
                object : DecisionObserver<Int, Act, Nothing, Notice> {
                    override fun onIgnored(
                        input: InputAttribution,
                        machineInput: MachineInput<Act>,
                        reason: IgnoreReason,
                    ) {
                        outcomes += "ignored:$reason"
                    }

                    override fun onFailed(
                        input: InputAttribution,
                        machineInput: MachineInput<Act>,
                        failure: FailureDescriptor,
                    ) {
                        outcomes += "failed"
                    }
                }
            val f = Fixture(this, observers = listOf(observer))
            val delivered = mutableListOf<Notice>()
            f.store.patch { plugin(eventPlugin { _, event -> delivered += event }) }
            try {
                f.store.startAndAwait()
                f.store.dispatchAndAwait(Act.Publish)
                val committed = f.store.currentState
                f.timeline.clear()
                delivered.clear()
                f.store.dispatchAndAwait(Act.Ignore)
                f.store.dispatchAndAwait(Act.Fail)
                runCurrent()
                assertSame(committed, f.store.currentState)
                assertTrue(f.timeline.isEmpty())
                assertTrue(delivered.isEmpty())
                assertEquals(listOf("ignored:NoTransition", "failed"), outcomes)
                assertEquals(1, f.errors.size)
                assertSame(f.failure, f.errors.single())
                f.store.dispatchAndAwait(Act.Publish)
                runCurrent()
                assertEquals(2, f.store.currentState.context)
                assertEquals(listOf(Notice.First, Notice.Second), delivered)
                assertEquals(
                    listOf(Notice.First, Notice.Second, Notice.First, Notice.Second),
                    f.store.mailbox.pending
                        .map { it.event },
                )
            } finally {
                f.store.closeAndJoin()
            }
        }

    @Test
    fun observerAndEventPluginFailuresAreIsolatedAndLaterCallbacksStillReceiveEachEffect() =
        runTest {
            val observerFailure = IllegalStateException("observer")
            val pluginFailure = IllegalStateException("plugin")
            val seen = mutableListOf<String>()
            val observers =
                listOf(
                    object : DecisionObserver<Int, Act, Nothing, Notice> {
                        override fun onCommitted(
                            input: InputAttribution,
                            machineInput: MachineInput<Act>,
                            decision: Decision<Int, Nothing, Notice>,
                        ) {
                            if (machineInput is MachineInput.Dispatch) throw observerFailure
                        }
                    },
                    object : DecisionObserver<Int, Act, Nothing, Notice> {
                        override fun onCommitted(
                            input: InputAttribution,
                            machineInput: MachineInput<Act>,
                            decision: Decision<Int, Nothing, Notice>,
                        ) {
                            if (machineInput is MachineInput.Dispatch) seen += "committed"
                        }
                    },
                )
            val f = Fixture(this, observers = observers)
            f.store.patch { plugin(eventPlugin { _, _ -> throw pluginFailure }, eventPlugin { _, event -> seen += event.name }) }
            try {
                f.store.startAndAwait()
                f.store.dispatchAndAwait(Act.Publish)
                runCurrent()
                assertEquals(listOf("committed", "First", "Second"), seen)
                assertEquals(1, f.errors.count { it === observerFailure })
                assertEquals(2, f.errors.count { it === pluginFailure })
                assertEquals(3, f.errors.size)
                assertEquals(1, f.store.currentState.context)
                assertEquals(
                    listOf(Notice.First, Notice.Second),
                    f.store.mailbox.pending
                        .map { it.event },
                )
            } finally {
                f.store.closeAndJoin()
            }
        }

    @Test
    fun suspendedEffectPublicationBlocksTheNextDecisionWithoutLosingItsPendingSnapshot() =
        runTest {
            val f = Fixture(this)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val delivered = mutableListOf<String>()
            f.store.patch {
                plugin(
                    eventPlugin { context, event ->
                        if (context == 1 && event == Notice.First) {
                            entered.complete(Unit)
                            release.await()
                        }
                        delivered += "$context:$event"
                    },
                )
            }
            try {
                f.store.startAndAwait()
                val first = async { f.store.dispatchAndAwait(Act.Publish) }
                entered.await()
                val second = async { f.store.dispatchAndAwait(Act.Publish) }
                runCurrent()
                assertFalse(first.isCompleted)
                assertFalse(second.isCompleted)
                assertEquals(1, f.reductions)
                assertEquals(1, f.store.currentState.context)
                release.complete(Unit)
                first.await()
                second.await()
                assertEquals(listOf("1:First", "1:Second", "2:First", "2:Second"), delivered)
                assertEquals(2, f.reductions)
                assertTrue(f.errors.isEmpty())
            } finally {
                release.complete(Unit)
                f.store.closeAndJoin()
            }
        }
}
