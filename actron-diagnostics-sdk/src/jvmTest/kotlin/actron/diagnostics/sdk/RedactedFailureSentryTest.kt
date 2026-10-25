package actron.diagnostics.sdk

import io.sentry.Breadcrumb
import io.sentry.IScopes
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.protocol.SentryId
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RedactedFailureSentryTest {
    @Test
    fun redactedFailureStillReportsErrorWithoutInventingTypeOrThrowable() {
        val calls = SentryCalls()
        val sink = SentryDiagnosticSink(calls.scopes)
        sink.emit(diagnosticEvent())
        sink.emit(diagnosticRedactedFailure())
        assertEquals(2, calls.breadcrumbs.size)
        val report = calls.reports.single()
        assertEquals(SentryLevel.ERROR, report.level)
        assertEquals("Command", report.getExtra("actron.failure.source"))
        assertEquals("handled", report.getExtra("actron.outcome"))
        assertFalse(checkNotNull(report.extras).containsKey("actron.failure.type"))
        assertFalse(report.throwable is Throwable, "redaction must not invent a Throwable")
        assertEquals("workflow", report.getExtra("actron.definition"))
    }
}

private class SentryCalls : InvocationHandler {
    val breadcrumbs = mutableListOf<Breadcrumb>()
    val reports = mutableListOf<SentryEvent>()
    val scopes = Proxy.newProxyInstance(IScopes::class.java.classLoader, arrayOf(IScopes::class.java), this) as IScopes

    override fun invoke(
        proxy: Any,
        method: Method,
        args: Array<out Any>,
    ): Any =
        when (method.name) {
            "addBreadcrumb" -> {
                breadcrumbs += args[0] as Breadcrumb
                Unit
            }

            "captureEvent" -> {
                reports += args[0] as SentryEvent
                SentryId.EMPTY_ID
            }

            else -> {
                error("Unexpected global SDK mutation ${method.name}")
            }
        }
}
