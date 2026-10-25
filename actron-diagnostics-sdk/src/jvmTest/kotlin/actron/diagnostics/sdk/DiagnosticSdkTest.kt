package actron.diagnostics.sdk

import actron.core.InputId
import actron.diagnostics.BehaviouralDiagnosticException
import actron.diagnostics.TraceOrigin
import actron.observability.StoreInstanceId
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.sentry.Breadcrumb
import io.sentry.IScopes
import io.sentry.SentryEvent
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticSdkTest {
    @Test fun otelUsesExplicitParentForEndedSpansAndClosesEveryReceiptSpan() {
        val exporter = checkNotNull(InMemorySpanExporter.create())
        val provider = checkNotNull(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
        try {
            val sink = OpenTelemetryDiagnosticSink(provider.get("test"))
            val first = diagnosticEvent()
            sink.emit(first)
            sink.emit(diagnosticChild(first.origin, diagnosticFailure("RuleFailure")))
            val spans = checkNotNull(exporter.finishedSpanItems).filterNotNull()
            assertEquals(2, spans.size)
            assertEquals(spans[0].spanId, spans[1].parentSpanId)
            assertEquals(spans[0].traceId, spans[1].traceId)
            assertEquals(StatusCode.ERROR, spans[1].status.statusCode)
            assertEquals("RuleFailure", spans[1].status.description)
            assertEquals("trace", spans[1].attributes.get(AttributeKey.stringKey("actron.trace")))
            assertEquals(listOf("transition", "command.registered"), checkNotNull(spans[0].events).filterNotNull().map { checkNotNull(it.name) })
        } finally {
            provider.close()
        }
    }

    @Test fun otelMarksUnavailableParentInsteadOfInventingAnSdkParent() {
        val exporter = checkNotNull(InMemorySpanExporter.create())
        val provider = checkNotNull(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
        try {
            OpenTelemetryDiagnosticSink(provider.get("test")).emit(diagnosticChild(TraceOrigin(StoreInstanceId("missing"), InputId(99), 99)))
            assertTrue(
                exporter.finishedSpanItems
                    .single()
                    .attributes
                    .get(AttributeKey.booleanKey("actron.otel.parent.missing")) == true,
            )
        } finally {
            provider.close()
        }
    }

    @Test fun sentryUsesReportLocalMetadataAndDoesNotAlterGlobalScope() {
        val breadcrumbs = mutableListOf<Breadcrumb>()
        val reports = mutableListOf<SentryEvent>()
        val scopes =
            Proxy.newProxyInstance(
                IScopes::class.java.classLoader,
                arrayOf(IScopes::class.java),
                object : java.lang.reflect.InvocationHandler {
                    override fun invoke(
                        proxy: Any,
                        method: java.lang.reflect.Method,
                        args: Array<out Any>,
                    ): Any =
                        when (method.name) {
                            "addBreadcrumb" -> {
                                breadcrumbs += args[0] as Breadcrumb
                                Unit
                            }

                            "captureEvent" -> {
                                reports += args[0] as SentryEvent
                                io.sentry.protocol.SentryId.EMPTY_ID
                            }

                            else -> {
                                error("Unexpected global SDK mutation ${method.name}")
                            }
                        }
                },
            ) as IScopes
        val sink = SentryDiagnosticSink(scopes)
        sink.emit(diagnosticEvent())
        sink.emit(diagnosticEvent(faults = diagnosticFailure("SafeType")))
        assertEquals(2, breadcrumbs.size)
        assertEquals("workflow", reports.single().getExtra("actron.definition"))
        assertTrue(reports.single().throwable is BehaviouralDiagnosticException)
        assertEquals("Actron workflow failure: SafeType", reports.single().throwable?.message)
    }
}
