package actron.diagnostics.sdk

import actron.diagnostics.FailureSource
import actron.diagnostics.TraceFault
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

class CrashlyticsRedactedFailureTest {
    @Test
    fun redactedCommandFailureIsReportedEvenWhenTheDecisionWasHandled() {
        val sdk = checkNotNull(mock(FirebaseCrashlytics::class.java))
        val event = crashlyticsEvent(listOf(TraceFault.of(FailureSource.Command, FailureDescriptor.Unavailable)))
        CrashlyticsDiagnosticSink(sdk).emit(event)
        verify(sdk).log("Actron workflow dispatch handled")
        val failure: ArgumentCaptor<Throwable> = checkNotNull(ArgumentCaptor.forClass(Throwable::class.java))
        val keys: ArgumentCaptor<CustomKeysAndValues> = checkNotNull(ArgumentCaptor.forClass(CustomKeysAndValues::class.java))
        verify(sdk).recordException(failure.capture(), keys.capture())
        assertFalse(checkNotNull(failure.value).cause is Throwable)
        assertEquals("Actron workflow failure: type metadata redacted", checkNotNull(failure.value).message)
        val attributes = checkNotNull(keys.value).reportKeys()
        assertFalse("actron.failure.type" in attributes)
        assertEquals("Command", attributes.getValue("actron.failure.source"))
        assertEquals("handled", attributes.getValue("actron.outcome"))
        verifyNoMoreInteractions(sdk)
    }
}
