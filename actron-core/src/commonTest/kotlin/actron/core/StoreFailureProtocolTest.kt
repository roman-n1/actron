@file:OptIn(actron.core.InternalActronApi::class)

package actron.core

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class StoreFailureProtocolTest {
    private data object Ready : State

    private data object Fail : Action

    @Test
    fun userCauseChainsStayIntactAcrossRecoveryAndBoundaryReporting() =
        runTest {
            val userCause = IllegalArgumentException("user cause")
            val original = IllegalStateException("original", userCause)
            val recoveryFailure = UnsupportedOperationException("recovery", userCause)
            val reported = mutableListOf<Throwable>()
            val store =
                createRuntimeStore<Ready, Fail, Nothing>(Ready) {
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    exceptionHandler(ExceptionHandler { reported += it })
                    state<Ready> {
                        action<Fail> { throw original }
                        recover<IllegalStateException> {
                            assertSame(original, error)
                            throw recoveryFailure
                        }
                    }
                }
            try {
                store.dispatchAndAwaitForTest(Fail)
                assertSame(recoveryFailure, reported.single())
                assertSame(userCause, recoveryFailure.cause)
                assertSame(userCause, original.cause)
                assertEquals(listOf<Throwable>(original), recoveryFailure.suppressedExceptions)
                assertEquals(Ready, store.currentState)
            } finally {
                store.close()
            }
        }
}
