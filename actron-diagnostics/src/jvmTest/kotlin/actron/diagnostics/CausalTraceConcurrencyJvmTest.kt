package actron.diagnostics

import actron.core.Action
import actron.core.Event
import actron.core.InputId
import actron.observability.StoreInstanceId
import actron.statechart.AtomicState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CausalTraceConcurrencyJvmTest {
    @Test
    fun concurrentObserversShareOneSequenceAndBoundedHistory() {
        val idle = StateId("idle")
        val machine =
            Machine<Int, Action, String, Event>(
                DefinitionId("concurrent"),
                DefinitionVersion("1"),
                StateChartDefinition(idle, listOf(AtomicState(idle)), emptyList()),
            )
        val input = MachineInput.Start(MachineTime.Zero)
        val explained = machine.decideExplained(machine.initialSnapshot(0), input)
        val hub = CausalTraceHub(capacity = 40)
        val executor = checkNotNull(Executors.newFixedThreadPool(4))
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        try {
            val producers: List<Future<Unit>> =
                (0 until 4).map { worker ->
                    val task: Future<Unit> =
                        checkNotNull(
                            executor.submit<Unit> {
                                val observer = hub.observer(machine, StoreInstanceId("store-$worker"))
                                ready.countDown()
                                check(start.await(10, TimeUnit.SECONDS))
                                repeat(25) { observer.onDecided(InputId(it.toLong()), input, explained) }
                            },
                        )
                    task
                }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            producers.forEach { it.get(10, TimeUnit.SECONDS) }
            val events = hub.snapshot()
            assertEquals((61L..100L).toList(), events.map { it.origin.sequence })
            assertEquals(40, events.map { it.origin }.toSet().size)
            assertTrue(events.all { it.lineage is TraceLineage.Root })
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }
}
