package actron.diagnostics.sdk

import actron.diagnostics.BehaviouralDiagnosticException
import actron.diagnostics.CausalTraceEvent
import actron.diagnostics.DiagnosticSink
import actron.diagnostics.attributes
import com.google.firebase.crashlytics.CustomKeysAndValues
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Android adapter; caller supplies an initialized SDK. No automatic collection or user identifiers.
 * Actual faults create a sanitized non-fatal report even when their type metadata is redacted.
 * Events without faults only add a breadcrumb. Keys belong to each report, never the global SDK.
 */
class CrashlyticsDiagnosticSink(
    private val crashlytics: FirebaseCrashlytics,
) : DiagnosticSink {
    override fun emit(event: CausalTraceEvent) {
        crashlytics.log("Actron ${event.definition.value} ${event.inputKind} ${event.outcome}")
        if (event.assessment.faults.isNotEmpty()) reportFailure(event)
    }

    private fun reportFailure(event: CausalTraceEvent) {
        val attributes = event.attributes()
        val failure =
            if ("actron.failure.type" in attributes) {
                BehaviouralDiagnosticException(attributes.getValue("actron.failure.type"))
            } else {
                RedactedWorkflowFailure()
            }
        val keys = CustomKeysAndValues.Builder()
        attributes.forEach { (key, value) -> keys.putString(key, value) }
        // Per-report attributes avoid mixing global custom keys between concurrent Stores.
        crashlytics.recordException(failure, keys.build())
    }
}

/** Crashlytics requires a Throwable; this fixed marker carries no invented original type or cause. */
private class RedactedWorkflowFailure : RuntimeException("Actron workflow failure: type metadata redacted")
