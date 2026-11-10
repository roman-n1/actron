package actron.timetravel.compose

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryContinuationRunnerTest {
    @Test
    fun keepsHistoryUntilSuccessAndAllowsRetryAfterFailure() =
        runTest {
            val runner = HistoryContinuationRunner()
            val release = CompletableDeferred<Unit>()
            var completed = 0
            runner.continueFrom(this, {
                release.await()
                error("Incompatible checkpoint")
            }) { completed++ }
            runner.continueFrom(this, {}) { completed++ }
            testScheduler.runCurrent()
            assertTrue(runner.running)
            assertEquals(0, completed)
            release.complete(Unit)
            testScheduler.runCurrent()
            assertFalse(runner.running)
            assertEquals("Incompatible checkpoint", runner.failure)
            assertEquals(0, completed)
            runner.continueFrom(this, {}) { completed++ }
            testScheduler.runCurrent()
            assertEquals(1, completed)
            assertEquals("", runner.failure)
        }
}
