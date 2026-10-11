package actron.diagnostics.sdk

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.sentry.*
import actron.core.*
import actron.diagnostics.*
import actron.observability.StoreInstanceId
import actron.statechart.*
import actron.statechart.machine.*
import java.lang.reflect.Proxy
import kotlin.test.*

class DiagnosticSdkTest {
    private data object Go : Action
    private fun event(
        parentage: (TraceOrigin) -> TraceLineage = { TraceLineage.Root(it, TraceId("trace")) },
        faults: List<TraceFault> = emptyList(),
        sequence: Long = 1,
    ): CausalTraceEvent {
        val origin = TraceOrigin(StoreInstanceId("s"), InputId(sequence), sequence)
        return CausalTraceEvent(DefinitionId("workflow"), DefinitionVersion("1"), origin, parentage(origin),
            "dispatch", MachineTime.Zero, TraceProvenance.Local, listOf(TransitionId(0)), listOf(CommandId(1)), emptyList(),
            TraceAssessment.Decided(DecisionExplanation(emptySet(), emptyList(), emptyList()), if (faults.isEmpty()) "handled" else "failed", faults))
    }
    private fun child(parent: TraceOrigin, faults: List<TraceFault> = emptyList()) =
        event({ TraceLineage.Linked(TraceId("trace"), parent, parent, false) }, faults, sequence = 2)
    private fun failure(type: String) = listOf(TraceFault.of(FailureSource.Decision, actron.observability.FailureDescriptor(type)))

    @Test fun otelUsesExplicitParentForEndedSpansAndClosesEveryReceiptSpan() {
        val exporter = checkNotNull(InMemorySpanExporter.create())
        val provider = checkNotNull(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
        try {
            val sink = OpenTelemetryDiagnosticSink(provider.get("test"))
            val first = event(); sink.emit(first); sink.emit(child(first.origin, failure("RuleFailure")))
            val spans = checkNotNull(exporter.finishedSpanItems).filterNotNull()
            assertEquals(2, spans.size)
            assertEquals(spans[0].spanId, spans[1].parentSpanId)
            assertEquals(spans[0].traceId, spans[1].traceId)
            assertEquals(StatusCode.ERROR, spans[1].status.statusCode)
            assertEquals("trace", spans[1].attributes.get(AttributeKey.stringKey("actron.trace")))
            assertEquals(listOf("transition", "command.registered"), checkNotNull(spans[0].events).filterNotNull().map { checkNotNull(it.name) })
        } finally { provider.close() }
    }

    @Test fun otelMarksUnavailableParentInsteadOfInventingAnSdkParent() {
        val exporter = checkNotNull(InMemorySpanExporter.create())
        val provider = checkNotNull(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
        try {
            OpenTelemetryDiagnosticSink(provider.get("test")).emit(child(TraceOrigin(StoreInstanceId("missing"), InputId(99), 99)))
            assertTrue(exporter.finishedSpanItems.single().attributes.get(AttributeKey.booleanKey("actron.otel.parent.missing")) == true)
        } finally { provider.close() }
    }

    @Test fun sentryUsesReportLocalMetadataAndDoesNotAlterGlobalScope() {
        val breadcrumbs = mutableListOf<Breadcrumb>(); val reports = mutableListOf<SentryEvent>()
        val scopes = Proxy.newProxyInstance(IScopes::class.java.classLoader, arrayOf(IScopes::class.java),
            object : java.lang.reflect.InvocationHandler {
                override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any>): Any = when (method.name) {
                    "addBreadcrumb" -> { breadcrumbs += args[0] as Breadcrumb; Unit }
                    "captureEvent" -> { reports += args[0] as SentryEvent; io.sentry.protocol.SentryId.EMPTY_ID }
                    else -> error("Unexpected global SDK mutation ${method.name}")
                }
            },
        ) as IScopes
        val sink = SentryDiagnosticSink(scopes)
        sink.emit(event()); sink.emit(event(faults = failure("SafeType")))
        assertEquals(2, breadcrumbs.size)
        assertEquals("workflow", reports.single().getExtra("actron.definition"))
        assertTrue(reports.single().throwable is BehaviouralDiagnosticException)
        assertEquals("Actron workflow failure: SafeType", reports.single().throwable?.message)
    }
}
