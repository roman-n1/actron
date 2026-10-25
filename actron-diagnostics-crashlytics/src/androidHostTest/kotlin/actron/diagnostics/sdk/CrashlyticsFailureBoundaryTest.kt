package actron.diagnostics.sdk

import actron.diagnostics.FailureSource
import actron.diagnostics.TraceFault
import actron.observability.FailureDescriptor
import com.google.firebase.crashlytics.CustomKeysAndValues
import com.google.firebase.crashlytics.FirebaseCrashlytics
import org.mockito.Mockito.any
import org.mockito.Mockito.anyString
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class CrashlyticsFailureBoundaryTest {
    private val event = crashlyticsEvent(listOf(TraceFault.of(FailureSource.Decision, FailureDescriptor("SafeType"))))

    @Test
    fun breadcrumbFailurePropagatesWithoutAttemptingAReport() {
        val sdk = checkNotNull(mock(FirebaseCrashlytics::class.java))
        val failure = IllegalStateException("SDK breadcrumb failed")
        doThrow(failure).`when`(sdk).log(anyString())
        assertSame(failure, assertFailsWith<IllegalStateException> { CrashlyticsDiagnosticSink(sdk).emit(event) })
        verify(sdk).log("Actron workflow dispatch handled")
        verifyNoMoreInteractions(sdk)
    }

    @Test
    fun reportFailurePropagatesAfterTheBreadcrumbWithoutRetry() {
        val sdk = checkNotNull(mock(FirebaseCrashlytics::class.java))
        val failure = IllegalStateException("SDK report failed")
        doThrow(failure).`when`(sdk).recordException(any(Throwable::class.java), any(CustomKeysAndValues::class.java))
        assertSame(failure, assertFailsWith<IllegalStateException> { CrashlyticsDiagnosticSink(sdk).emit(event) })
        val order = checkNotNull(inOrder(sdk))
        order.verify(sdk).log("Actron workflow dispatch handled")
        order.verify(sdk).recordException(any(Throwable::class.java), any(CustomKeysAndValues::class.java))
        verifyNoMoreInteractions(sdk)
    }
}
