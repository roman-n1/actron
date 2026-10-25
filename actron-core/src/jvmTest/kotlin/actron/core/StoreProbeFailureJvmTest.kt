@file:OptIn(InternalActronApi::class)

package actron.core

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Counter --Bump--> Counter. A ProcessingStarted probe fails before the handler can run. */
class StoreProbeFailureJvmTest {
    private data class Counter(
        val count: Int = 0,
    ) : State

    private data object Bump : Action

    @Test fun fatalProcessingStartProbeStillFinishesInputAndReleasesPendingWork() = failure(false)

    @Test fun rethrowingProcessingStartProbeHandlerStillFinishesInputAndReleasesPendingWork() {
        // A real Rethrow reaches coroutine-test's global exception collector too. Keep that
        // deliberate uncaught failure in a fresh process, without contaminating later tests.
        val log = checkNotNull(Files.createTempFile("actron-core-probe-", ".log").toFile())
        val paths =
            buildList<String> {
                var loader = checkNotNull(javaClass.classLoader)
                while (true) {
                    if (loader is URLClassLoader) {
                        for (url in checkNotNull(loader.urLs).filterNotNull()) add(File(url.toURI()).path)
                    }
                    loader = loader.parent ?: break
                }
            }
        val classpath = checkNotNull((paths + checkNotNull(System.getProperty("java.class.path")).split(File.pathSeparator)).distinct())
        val child =
            checkNotNull(
                ProcessBuilder(
                    File(System.getProperty("java.home"), "bin/java").path,
                    "-cp",
                    classpath.joinToString(File.pathSeparator),
                    CoreProbeFailureProcess::class.java.name,
                ).redirectErrorStream(true)
                    .redirectOutput(log)
                    .start(),
            )
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Rethrow child timed out")
            assertEquals(0, child.exitValue(), log.readText())
            assertTrue(log.readText().contains("OK core rethrow"), log.readText())
        } finally {
            if (child.isAlive) child.destroyForcibly().waitFor()
            log.delete()
        }
    }

    private fun failingStartProbe(
        traces: ConcurrentLinkedQueue<StoreTrace<Counter, Bump, Nothing>>,
        armed: AtomicBoolean,
        closed: CountDownLatch,
        rethrow: Boolean,
    ): StoreProbe<Counter, Bump, Nothing> =
        StoreProbe { trace ->
            traces += trace
            if (trace is StoreTrace.ProcessingStarted && armed.compareAndSet(true, false)) {
                if (rethrow) throw IllegalStateException("probe failure") else throw AssertionError("fatal probe failure")
            }
            if (trace === StoreTrace.StoreClosed) closed.countDown()
        }

    internal fun failure(rethrow: Boolean) =
        runBlocking {
            val traces = ConcurrentLinkedQueue<StoreTrace<Counter, Bump, Nothing>>()
            val errors = LinkedBlockingQueue<Throwable>()
            val armed = AtomicBoolean(false)
            val closed = CountDownLatch(1)
            val dispatcher =
                Executors
                    .newSingleThreadExecutor { runnable: Runnable ->
                        Thread(runnable, "actron-core-probe-failure").apply {
                            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _: Thread, error: Throwable -> errors.offer(error) }
                        }
                    }.asCoroutineDispatcher()
            val store =
                createRuntimeStore<Counter, Bump, Nothing>(Counter()) {
                    coroutineContext(dispatcher)
                    exceptionHandler(if (rethrow) ExceptionHandler.Rethrow else ExceptionHandler { errors.offer(it) })
                    probe(failingStartProbe(traces, armed, closed, rethrow))
                    state<Counter> { action<Bump> { nextState { state.copy(count = state.count + 1) } } }
                }

            @Suppress("UNCHECKED_CAST")
            val api = store as StoreInternalApi<Counter, Bump, Nothing>
            try {
                withTimeout(10_000) { api.startAndAwait() }
                armed.set(true)
                withTimeout(10_000) { api.dispatchAndAwait(Bump) }
                kotlin.test.assertNotNull(errors.poll(10, TimeUnit.SECONDS))
                assertEquals(Counter(), store.currentState)
                assertTrue(api.awaitIdle(Duration.ZERO).isIdle, "Failure at start must release pendingInputs")
                val actionInput =
                    traces
                        .filterIsInstance<StoreTrace.InputAccepted<Bump>>()
                        .last { it.kind is InputKind.Dispatch }
                        .input
                val finish = traces.filterIsInstance<StoreTrace.ProcessingFinished>().single { it.input == actionInput }
                assertIs<ProcessingOutcome.Failed>(finish.outcome)
                withTimeout(10_000) { api.dispatchAndAwait(Bump) }
                assertEquals(Counter(1), store.currentState)
                assertTrue(api.awaitIdle(Duration.ZERO).isIdle)
                store.close()
                assertTrue(closed.await(10, TimeUnit.SECONDS))
                assertTrue(api.awaitIdle(Duration.ZERO).isIdle)
            } finally {
                store.close()
                dispatcher.close()
            }
        }

    // Runs in the existing isolated process: the expected uncaught handler error must not reach
    // coroutine-test's global exception collector in the Gradle test worker.
    internal fun postPublicationHandlerFailure() =
        runBlocking {
            for (failInSaver in listOf(true, false)) {
                val original = IllegalStateException("post-publication")
                val handlerFailure = IllegalArgumentException("handler failed")
                val calls = ConcurrentLinkedQueue<Throwable>()
                val uncaught = LinkedBlockingQueue<Throwable>()
                val traces = ConcurrentLinkedQueue<StoreTrace<Counter, Bump, Nothing>>()
                var recovered = 0
                val dispatcher =
                    Executors
                        .newSingleThreadExecutor { runnable: Runnable ->
                            Thread(runnable, "actron-core-reporting-failure").apply {
                                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _: Thread, error: Throwable -> uncaught.offer(error) }
                            }
                        }.asCoroutineDispatcher()
                val store =
                    createRuntimeStore<Counter, Bump, Nothing>(Counter()) {
                        coroutineContext(dispatcher)
                        probe(StoreProbe { traces += it })
                        exceptionHandler(
                            ExceptionHandler {
                                calls += it
                                throw handlerFailure
                            },
                        )
                        stateSaver(StateSaver(save = { if (failInSaver) throw original }, restore = { it }))
                        plugin(Plugin(onState = { _, _ -> if (!failInSaver) throw original }))
                        state<Counter> {
                            action<Bump> { nextState { Counter(1) } }
                            recover<Exception> { recovered++ }
                        }
                    }
                try {
                    withTimeout(10_000) { store.dispatchAndAwaitForTest(Bump) }
                    val escaped = assertNotNull(uncaught.poll(10, TimeUnit.SECONDS))
                    assertSame(handlerFailure, escaped.cause)
                    assertEquals(listOf<Throwable>(original), calls.toList())
                    assertEquals(Counter(1), store.currentState)
                    assertEquals(0, recovered)
                    assertEquals(listOf(StoreTrace.FailureReported(InputId(2), original)), traces.filterIsInstance<StoreTrace.FailureReported>())
                    assertEquals(ProcessingOutcome.Failed(handlerFailure), traces.filterIsInstance<StoreTrace.ProcessingFinished>().last().outcome)
                } finally {
                    store.close()
                    dispatcher.close()
                }
            }
        }
}

/** Exercises the actual Rethrow handler, including its uncaught coroutine error. */
object CoreProbeFailureProcess {
    @JvmStatic fun main(args: Array<String>) {
        StoreProbeFailureJvmTest().failure(true)
        StoreProbeFailureJvmTest().postPublicationHandlerFailure()
        println("OK core rethrow")
    }
}
