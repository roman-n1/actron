package actron.diagnostics.sdk

import actron.diagnostics.BehaviouralDiagnosticException
import actron.diagnostics.CausalTraceEvent
import actron.diagnostics.DiagnosticSink
import actron.diagnostics.attributes
import io.sentry.Breadcrumb
import io.sentry.IScopes
import io.sentry.SentryEvent
import io.sentry.SentryLevel

/** Caller owns Sentry initialization/consent/transport. Events use local extras, not global scope keys. */
class SentryDiagnosticSink(
    private val scopes: IScopes,
) : DiagnosticSink {
    override fun emit(event: CausalTraceEvent) {
        val attributes = event.attributes()
        val breadcrumb =
            Breadcrumb().apply {
                category = "actron"
                type = "statechart"
                message = event.outcome
                attributes.forEach { (key, value) -> setData(key, value) }
            }
        scopes.addBreadcrumb(breadcrumb)
        if (event.assessment.faults.isEmpty()) return
        val report = SentryEvent()
        event.withFailureType { report.throwable = BehaviouralDiagnosticException(it) }
        report.level = SentryLevel.ERROR
        attributes.forEach { (key, value) -> report.setExtra(key, value) }
        scopes.captureEvent(report)
    }
}
