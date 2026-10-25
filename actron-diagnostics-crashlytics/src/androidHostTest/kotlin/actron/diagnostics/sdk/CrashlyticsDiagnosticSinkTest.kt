package actron.diagnostics.sdk

import actron.diagnostics.FailureSource
import actron.diagnostics.TraceFault
import actron.diagnostics.attributes
import actron.observability.FailureDescriptor
import com.google.firebase.crashlytics.CustomKeysAndValues
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.crashlytics.reportKeys
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CrashlyticsDiagnosticSinkTest {
    @Test
    fun sendsPerReportKeysWithoutGlobalCustomKeyMutationOrSdkInitialization() {
        val sdk = checkNotNull(mock(FirebaseCrashlytics::class.java))
        val event = crashlyticsEvent(listOf(TraceFault.of(FailureSource.Decision, FailureDescriptor("SafeType"))), outcome = "failed")
        CrashlyticsDiagnosticSink(sdk).emit(event)
        verify(sdk).log("Actron workflow dispatch failed")
        val failure: ArgumentCaptor<Throwable> = checkNotNull(ArgumentCaptor.forClass(Throwable::class.java))
        val keys: ArgumentCaptor<CustomKeysAndValues> = checkNotNull(ArgumentCaptor.forClass(CustomKeysAndValues::class.java))
        verify(sdk).recordException(failure.capture(), keys.capture())
        val report: Throwable = checkNotNull(failure.value)
        assertEquals("Actron workflow failure: SafeType", report.message)
        assertFalse(report.cause is Throwable)
        assertEquals(event.attributes(), checkNotNull(keys.value).reportKeys())
        verifyNoMoreInteractions(sdk)
    }

    @Test
    fun outcomeTextWithoutFaultsOnlyAddsABreadcrumb() {
        val sdk = checkNotNull(mock(FirebaseCrashlytics::class.java))
        val sink = CrashlyticsDiagnosticSink(sdk)
        sink.emit(crashlyticsEvent())
        sink.emit(crashlyticsEvent(outcome = "failed"))
        verify(sdk).log("Actron workflow dispatch handled")
        verify(sdk).log("Actron workflow dispatch failed")
        verifyNoMoreInteractions(sdk)
    }

    @Test
    fun severalFaultsProduceOneReportUsingTheFirstAvailableType() {
        val sdk = checkNotNull(mock(FirebaseCrashlytics::class.java))
        val event =
            crashlyticsEvent(
                listOf(
                    TraceFault.of(FailureSource.Decision, FailureDescriptor.Unavailable),
                    TraceFault.of(FailureSource.Decision, FailureDescriptor("KnownType")),
                    TraceFault.of(FailureSource.Command, FailureDescriptor("LaterType")),
                ),
            )
        CrashlyticsDiagnosticSink(sdk).emit(event)
        verify(sdk).log("Actron workflow dispatch handled")
        val failure: ArgumentCaptor<Throwable> = checkNotNull(ArgumentCaptor.forClass(Throwable::class.java))
        val keys: ArgumentCaptor<CustomKeysAndValues> = checkNotNull(ArgumentCaptor.forClass(CustomKeysAndValues::class.java))
        verify(sdk).recordException(failure.capture(), keys.capture())
        assertEquals("Actron workflow failure: KnownType", checkNotNull(failure.value).message)
        assertEquals("KnownType", checkNotNull(keys.value).reportKeys().getValue("actron.failure.type"))
        verifyNoMoreInteractions(sdk)
    }

    @Test
    fun originalExceptionMessageAndCauseAreNotRetained() {
        val sdk = checkNotNull(mock(FirebaseCrashlytics::class.java))
        val original = IllegalStateException("private payload")
        val event = crashlyticsEvent(listOf(TraceFault.of(FailureSource.Decision, FailureDescriptor.of(original))))
        CrashlyticsDiagnosticSink(sdk).emit(event)
        verify(sdk).log("Actron workflow dispatch handled")
        val failure: ArgumentCaptor<Throwable> = checkNotNull(ArgumentCaptor.forClass(Throwable::class.java))
        val keys: ArgumentCaptor<CustomKeysAndValues> = checkNotNull(ArgumentCaptor.forClass(CustomKeysAndValues::class.java))
        verify(sdk).recordException(failure.capture(), keys.capture())
        val report: Throwable = checkNotNull(failure.value)
        assertFalse(report.message.orEmpty().contains("private payload"))
        assertFalse(report.cause is Throwable)
        assertFalse(checkNotNull(keys.value).reportKeys().values.any { "private payload" in it })
        verifyNoMoreInteractions(sdk)
    }
}
