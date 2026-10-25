package actron.test

import actron.core.PluginLaunchScope
import actron.core.PluginScope
import actron.core.State
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecorderOwnershipTest {
    @Test
    fun concurrentStartupAcceptsExactlyOneOwnerPerRecorder() {
        val recorders = List(2_000) { StoreRecorder<Marker, Nothing, Nothing>() }
        val successes = AtomicIntegerArray(recorders.size)
        val barrier = CyclicBarrier(2)
        val executor = checkNotNull(Executors.newFixedThreadPool(2))
        try {
            val tasks: List<Future<Unit>> =
                List(2) {
                    val scope = MarkerScope()
                    val task: Future<Unit> =
                        checkNotNull(
                            executor.submit<Unit> {
                                runBlocking {
                                    recorders.forEachIndexed { index, recorder ->
                                        barrier.await(10, TimeUnit.SECONDS)
                                        try {
                                            recorder.onStart(scope, Marker)
                                            successes.incrementAndGet(index)
                                        } catch (failure: IllegalStateException) {
                                            assertTrue("one Store" in failure.message.orEmpty())
                                        }
                                        barrier.await(10, TimeUnit.SECONDS)
                                    }
                                }
                                Unit
                            },
                        )
                    task
                }
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
            recorders.forEachIndexed { index, recorder ->
                assertEquals(1, successes.get(index), "recorder $index accepted more than one owner")
                assertEquals(listOf(Marker), recorder.states)
            }
        } finally {
            executor.shutdownNow()
        }
    }
}

private data object Marker : State

private class MarkerScope : PluginScope<Marker, Nothing> {
    override fun dispatch(action: Nothing) = Unit

    override fun launch(
        dispatcher: CoroutineContext,
        block: suspend PluginLaunchScope<Marker, Nothing>.() -> Unit,
    ) = error("Recorder startup must not launch work")
}
