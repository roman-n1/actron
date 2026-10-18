package actron.statechart.machine

import actron.core.Action
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CommandHandlersTest {
    private data class Answer(
        val value: String,
    ) : Action

    private fun envelope(value: String) = CommandEnvelope(CommandId(1), value, ActivationId(1))

    @Test
    fun returnsSuccessAndMapsServiceFailure() =
        runTest {
            val answers = mutableListOf<Answer>()
            val handler =
                commandHandler<String, Answer>(onFailure = { request, _ -> Answer("failed:$request") }) {
                    if (it == "bad") error("offline")
                    Answer(it)
                }
            handler.execute(envelope("good"), answers::add)
            handler.execute(envelope("bad"), answers::add)
            assertEquals(listOf(Answer("good"), Answer("failed:bad")), answers)
        }

    @Test
    fun cancellationIsNotAnErrorAction() =
        runTest {
            val handler =
                commandHandler<String, Answer>(onFailure = { _, _ -> error("must not map cancellation") }) {
                    throw CancellationException("cancelled")
                }
            assertFailsWith<CancellationException> { handler.execute(envelope("query")) { error("must not emit") } }
        }

    @Test
    fun cancelledNonCooperativeRequestCannotEmitOrMapFailure() =
        runTest {
            for (fails in listOf(false, true)) {
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val answers = mutableListOf<Answer>()
                val handler =
                    commandHandler<String, Answer>(onFailure = { _, _ -> error("cancelled request must not map failure") }) {
                        withContext(NonCancellable) {
                            started.complete(Unit)
                            release.await()
                            if (fails) error("late failure")
                            Answer("late success")
                        }
                    }
                val job = launch { handler.execute(envelope("query"), answers::add) }
                started.await()
                job.cancel()
                release.complete(Unit)
                job.cancelAndJoin()
                assertTrue(answers.isEmpty())
            }
        }

    @Test
    fun typedRoutingRejectsMissingAndAmbiguousHandlersBeforeExecuting() =
        runTest {
            val answers = mutableListOf<Answer>()
            val handler =
                commandHandlers<Any, Answer> {
                    handle<String>(onFailure = { _, _ -> Answer("failure") }) { Answer(it) }
                }
            handler.execute(envelope("typed"), answers::add)
            assertEquals(listOf(Answer("typed")), answers)
            assertFailsWith<IllegalStateException> {
                handler.execute(CommandEnvelope(CommandId(2), 42, ActivationId(1)), answers::add)
            }
            val ambiguous =
                commandHandlers<Any, Answer> {
                    handle<Any>(onFailure = { _, _ -> error("unexpected") }) { error("must not execute") }
                    handle<String>(onFailure = { _, _ -> error("unexpected") }) { error("must not execute") }
                }
            assertFailsWith<IllegalStateException> { ambiguous.execute(envelope("typed"), answers::add) }
        }
}
