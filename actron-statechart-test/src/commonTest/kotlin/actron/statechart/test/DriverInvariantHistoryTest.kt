@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.Machine
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DriverInvariantHistoryTest {
    private data object Tick : Action

    private val idle = StateId("idle")
    private val machine =
        Machine<Int, Action, String, Event>(
            DefinitionId("driver-invariants"),
            DefinitionVersion("1"),
            StateChartDefinition(idle, listOf(AtomicState(idle)), emptyList()),
        ) {
            invariant("zero") { it.context == 0 }
            onAction(idle, ActionMatcher.of<Tick>("tick")) { context++ }
        }

    @Test
    fun clearingRecordedHistoryDoesNotSkipTheNextInvariantCheck() =
        runTest {
            val driver = MachineTestDriver(machine, 0, this)
            try {
                driver.start()
                driver.recorder.clear()
                val failure = assertFailsWith<AssertionError> { driver.send(Tick) }
                assertTrue("zero" in failure.message.orEmpty())
            } finally {
                driver.close()
            }
        }

    @Test
    fun replacingHistoryWithTheSameNumberOfSnapshotsStillChecksIt() =
        runTest {
            val driver = MachineTestDriver(machine, 0, this)
            try {
                driver.start()
                val previousSize = driver.recorder.states.size
                driver.recorder.clear()
                repeat(previousSize) { driver.sendLater(Tick) }
                runCurrent()
                val failure = assertFailsWith<AssertionError> { driver.assertInvariants() }
                assertTrue("revision 2" in failure.message.orEmpty(), failure.message.orEmpty())
            } finally {
                driver.close()
            }
        }
}
