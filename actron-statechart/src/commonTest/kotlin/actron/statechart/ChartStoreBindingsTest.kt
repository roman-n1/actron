@file:OptIn(actron.core.ExperimentalActronApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.statechart

import actron.core.Action
import actron.core.Event
import actron.test.dispatchAndAwait
import actron.test.startAndAwait
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ChartStoreBindingsTest {
    private data object Go : Action

    private data object Back : Action

    private data object Note : Event

    private val a = StateId("a")
    private val b = StateId("b")
    private val c = StateId("c")
    private val d = StateId("d")

    @Test
    fun retainedBuilderCannotChangeHooksOrActivitiesOfAnAlreadyBuiltStore() =
        runTest {
            val captured = CompletableDeferred<StateChartStoreBuilder<Int, Action, Note>>()
            var activities = 0
            var cancelled = 0
            var lateActivities = 0
            val chart = StateChartDefinition(a, listOf(AtomicState(a), AtomicState(b)), listOf(Transition(a, b, ActionMatcher.of<Go>("go")), Transition(b, a, ActionMatcher.of<Back>("back"))))
            val store =
                StateChartStore<Int, Action, Note>(chart, 0, coroutineContext) {
                    captured.complete(this)
                    onEnter(a) { context++ }
                    onExit(a) { context += 10 }
                    activity(a) {
                        activities++
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled++
                        }
                    }
                }
            captured.await().apply {
                onEnter(a) { context += 1000 }
                onExit(a) { context += 1000 }
                activity(a) { lateActivities++ }
            }
            try {
                store.startAndAwait()
                runCurrent()
                assertEquals(1, store.currentState.context)
                store.dispatchAndAwait(Go)
                runCurrent()
                assertEquals(11, store.currentState.context)
                store.dispatchAndAwait(Back)
                runCurrent()
                assertEquals(12, store.currentState.context)
                assertEquals(2, activities)
                assertEquals(1, cancelled)
                assertEquals(0, lateActivities)
            } finally {
                store.close()
                runCurrent()
            }
            assertEquals(2, cancelled)
        }

    @Test
    fun automaticStepLimitIsFrozenAtBuildAlongsideHookRegistrations() =
        runTest {
            val captured = CompletableDeferred<StateChartStoreBuilder<Int, Go, Note>>()
            val chart = StateChartDefinition(a, listOf(AtomicState(a), AtomicState(b), AtomicState(c), AtomicState(d)), listOf(Transition(a, b, ActionMatcher.of<Go>("go")), Transition(b, c, Trigger.Eventless), Transition(c, d, Trigger.Eventless)))
            val store =
                StateChartStore<Int, Go, Note>(chart, 0, coroutineContext) {
                    captured.complete(this)
                    maxMicrosteps(4)
                    store { exceptionHandler(actron.core.ExceptionHandler.Ignore) }
                }
            captured.await().maxMicrosteps(1)
            try {
                store.startAndAwait()
                store.dispatchAndAwait(Go)
                assertTrue(store.currentState.isActive(d))
            } finally {
                store.close()
            }
        }

    @Test
    fun failedAutomaticStepDropsTentativeWorkAndKeepsThePreviousActivityAndTimerAlive() =
        runTest {
            val failure = IllegalStateException("entry failed")
            val errors = mutableListOf<Throwable>()
            var forbidden = 0
            var cancelled = 0
            val chart = StateChartDefinition(a, listOf(AtomicState(a), AtomicState(b), AtomicState(c), AtomicState(d)), listOf(Transition(a, b, ActionMatcher.of<Go>("go")), Transition(b, c, Trigger.Eventless), Transition(a, d, Trigger.After(20.milliseconds))))
            val store =
                StateChartStore<Int, Go, Note>(chart, 0, coroutineContext) {
                    onEnter(b) { launch { forbidden++ } }
                    activity(b) { forbidden++ }
                    onEnter(c) { throw failure }
                    activity(a) {
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled++
                        }
                    }
                    store { exceptionHandler(actron.core.ExceptionHandler { errors += it }) }
                }
            try {
                store.startAndAwait()
                runCurrent()
                val before = store.currentState
                store.dispatchAndAwait(Go)
                runCurrent()
                assertSame(before, store.currentState)
                assertEquals(0, forbidden)
                assertEquals(0, cancelled)
                assertSame(failure, errors.single())
                advanceTimeBy(20)
                runCurrent()
                assertTrue(store.currentState.isActive(d))
                assertEquals(1, cancelled)
                assertEquals(0, forbidden)
            } finally {
                store.close()
            }
        }
}
