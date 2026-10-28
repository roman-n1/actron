package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.core.StateSaver
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.test.dispatchAndAwait
import actron.test.patch
import actron.test.startAndAwait
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ResumePersistenceTest {
    private data object Tick : Action

    private data object Note : Event

    private val node = StateId("active")
    private val machine =
        Machine<Int, Tick, Nothing, Note>(
            DefinitionId("persistence"),
            DefinitionVersion("1"),
            StateChartDefinition(node, listOf(AtomicState(node)), emptyList()),
        ) {
            onAction(node, ActionMatcher.of<Tick>("tick")) { context++ }
        }
    private val handler = CommandHandler<Nothing, Tick> { _, _ -> }

    private fun checkpoint() = ExecutorCheckpoint<Int, Nothing>(machine.decide(machine.initialSnapshot(1), MachineInput.Start(MachineTime.Zero)).snapshot, MachineTime.Zero)

    @Test
    fun builderSaverPersistsNewCommitsWithoutRestoringOverTheCheckpoint() = checkSaver(patched = false)

    @Test
    fun patchedSaverPersistsNewCommitsWithoutRestoringOverTheCheckpoint() = checkSaver(patched = true)

    private fun checkSaver(patched: Boolean) =
        runTest {
            val checkpoint = checkpoint()
            var restores = 0
            val saved = mutableListOf<MachineSnapshot<Int>>()
            val saver =
                StateSaver<MachineSnapshot<Int>>(
                    save = { saved += it },
                    restore = {
                        restores++
                        checkpoint.snapshot.copy(context = 99)
                    },
                )
            val store =
                resumeMachineStore(machine, checkpoint, handler, backgroundScope, coroutineContext = StandardTestDispatcher(testScheduler)) {
                    if (!patched) stateSaver(saver)
                }
            try {
                if (patched) store.patch { stateSaver(saver) }
                assertSame(checkpoint.snapshot, store.currentState)
                store.startAndAwait()
                assertSame(checkpoint.snapshot, store.checkpoint().snapshot)
                assertEquals(0, restores)
                store.dispatchAndAwait(Tick)
                assertEquals(2, store.currentState.context)
                assertEquals(listOf(store.currentState), saved)
                assertEquals(store.currentState, store.checkpoint().snapshot)
            } finally {
                store.closeAndJoin()
            }
        }

    @Test
    fun checkpointRemainsTheRestoreSourceWhenInitialStateIsPatchedWithoutASaver() =
        runTest {
            val checkpoint = checkpoint()
            val store = resumeMachineStore(machine, checkpoint, handler, backgroundScope, coroutineContext = StandardTestDispatcher(testScheduler))
            try {
                store.patch { initialState(checkpoint.snapshot.copy(context = 99)) }
                assertSame(checkpoint.snapshot, store.currentState)
                store.startAndAwait()
                assertSame(checkpoint.snapshot, store.checkpoint().snapshot)
            } finally {
                store.closeAndJoin()
            }
        }

    @Test
    fun freshMachineStoresKeepTheOrdinarySaverRestoreContract() =
        runTest {
            var restores = 0
            val restored = checkpoint().snapshot.copy(context = 99)
            val saved = mutableListOf<MachineSnapshot<Int>>()
            val store =
                MachineStore(machine, 0, handler, backgroundScope, coroutineContext = StandardTestDispatcher(testScheduler)) {
                    stateSaver(
                        StateSaver(save = { saved += it }, restore = {
                            restores++
                            restored
                        }),
                    )
                }
            try {
                assertSame(restored, store.currentState)
                store.startAndAwait()
                store.dispatchAndAwait(Tick)
                assertEquals(1, restores)
                assertEquals(100, store.currentState.context)
                assertEquals(listOf(100), saved.map { it.context })
            } finally {
                store.closeAndJoin()
            }
        }
}
