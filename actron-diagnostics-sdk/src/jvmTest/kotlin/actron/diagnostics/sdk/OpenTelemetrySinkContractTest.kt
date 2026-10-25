package actron.diagnostics.sdk

import actron.core.InputId
import actron.diagnostics.TraceLineage
import actron.diagnostics.TraceOrigin
import actron.observability.StoreInstanceId
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Scope
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OpenTelemetrySinkContractTest {
    @Test
    fun redactedFailureStillMarksSpanAsErrorWithoutInventingType() {
        withTelemetry { fixture ->
            OpenTelemetryDiagnosticSink(fixture.tracer).emit(diagnosticRedactedFailure())
            val span = fixture.spans().single()
            assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, span.status.statusCode)
            assertEquals("", span.status.description)
            assertEquals("Command", span.attributes.get(AttributeKey.stringKey("actron.failure.source")))
            assertEquals("handled", span.attributes.get(AttributeKey.stringKey("actron.outcome")))
            assertTrue(AttributeKey.stringKey("actron.failure.type") !in span.attributes.asMap())
        }
    }

    @Test
    fun invalidParentContextIsReportedAsMissing() {
        withTelemetry { fixture ->
            val sink = OpenTelemetryDiagnosticSink(PresetSpansTracer(fixture.tracer, listOf(Span.getInvalid())))
            val parent = diagnosticEvent()
            sink.emit(parent)
            sink.emit(diagnosticChild(parent.origin))
            val child = fixture.spans().single()
            assertEquals(Span.getInvalid().spanContext.spanId, child.parentSpanId)
            assertEquals(true, child.attributes.get(parentMissing))
        }
    }

    @Test
    fun validUnsampledParentContextIsRetained() {
        withTelemetry { fixture ->
            val context = unsampledParentContext()
            val sink = OpenTelemetryDiagnosticSink(PresetSpansTracer(fixture.tracer, listOf(Span.wrap(context))))
            val parent = diagnosticEvent()
            sink.emit(parent)
            sink.emit(diagnosticChild(parent.origin))
            val child = fixture.spans().single()
            assertEquals(context.spanId, child.parentSpanId)
            assertEquals(context.traceId, child.traceId)
            assertEquals(false, child.attributes.get(parentMissing))
        }
    }

    @Test
    fun invalidContextDoesNotEvictAValidParent() {
        withTelemetry { fixture ->
            val context = unsampledParentContext()
            val tracer = PresetSpansTracer(fixture.tracer, listOf(Span.wrap(context), Span.getInvalid()))
            val sink = OpenTelemetryDiagnosticSink(tracer, capacity = 1)
            val parent = diagnosticEvent()
            sink.emit(parent)
            sink.emit(diagnosticEvent(sequence = 3))
            sink.emit(diagnosticChild(parent.origin))
            val child = fixture.spans().single()
            assertEquals(context.spanId, child.parentSpanId)
            assertEquals(false, child.attributes.get(parentMissing))
        }
    }

    @Test
    fun invalidReplacementRemovesThePreviousContextForThatOrigin() {
        withTelemetry { fixture ->
            val tracer = PresetSpansTracer(fixture.tracer, listOf(Span.wrap(unsampledParentContext()), Span.getInvalid()))
            val sink = OpenTelemetryDiagnosticSink(tracer)
            val parent = diagnosticEvent()
            sink.emit(parent)
            sink.emit(parent)
            sink.emit(diagnosticChild(parent.origin))
            val child = fixture.spans().single()
            assertEquals(Span.getInvalid().spanContext.spanId, child.parentSpanId)
            assertEquals(true, child.attributes.get(parentMissing))
        }
    }

    @Test
    fun concurrentStoresKeepTheirOwnParentContextsAndEndEverySpan() {
        withTelemetry { fixture ->
            val sink = OpenTelemetryDiagnosticSink(fixture.tracer, capacity = 100)
            val executor = checkNotNull(Executors.newFixedThreadPool(4))
            val ready = CountDownLatch(4)
            val start = CountDownLatch(1)
            try {
                val producers: List<Future<Unit>> =
                    (0 until 4).map { worker ->
                        val task: Future<Unit> =
                            checkNotNull(
                                executor.submit<Unit> {
                                    ready.countDown()
                                    check(start.await(10, TimeUnit.SECONDS))
                                    repeat(10) { index ->
                                        val origin = TraceOrigin(StoreInstanceId("store-$worker"), InputId(index * 2L + 1), index * 2L + 1)
                                        val root = diagnosticEvent().copy(origin = origin, lineage = TraceLineage.Root(origin))
                                        sink.emit(root)
                                        val childOrigin = origin.copy(input = InputId(index * 2L + 2), sequence = index * 2L + 2)
                                        sink.emit(root.copy(origin = childOrigin, lineage = TraceLineage.Linked(root.trace, origin, origin, false)))
                                    }
                                },
                            )
                        task
                    }
                assertTrue(ready.await(10, TimeUnit.SECONDS))
                start.countDown()
                producers.forEach { it.get(10, TimeUnit.SECONDS) }
                val spans = fixture.spans()
                assertEquals(80, spans.size)
                val byOrigin =
                    spans.associateBy {
                        checkNotNull(it.attributes.get(AttributeKey.stringKey("actron.store"))) to
                            checkNotNull(it.attributes.get(AttributeKey.stringKey("actron.sequence")))
                    }
                assertEquals(80, byOrigin.size)
                repeat(4) { worker ->
                    repeat(10) { index ->
                        val root = byOrigin.getValue("store-$worker" to (index * 2L + 1).toString())
                        val child = byOrigin.getValue("store-$worker" to (index * 2L + 2).toString())
                        assertEquals(root.spanId, child.parentSpanId)
                        assertEquals(root.traceId, child.traceId)
                        assertEquals(false, child.attributes.get(parentMissing))
                    }
                }
            } finally {
                start.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun rejectsNonPositiveCapacity() {
        withTelemetry { fixture ->
            assertFailsWith<IllegalArgumentException> { OpenTelemetryDiagnosticSink(fixture.tracer, 0) }
            assertFailsWith<IllegalArgumentException> { OpenTelemetryDiagnosticSink(fixture.tracer, -1) }
        }
    }

    @Test
    fun replacementOfOriginUsesLatestContextWithoutIncreasingCacheSize() {
        withTelemetry { fixture ->
            val sink = OpenTelemetryDiagnosticSink(fixture.tracer, capacity = 2)
            val first = diagnosticEvent(sequence = 1)
            sink.emit(first)
            sink.emit(diagnosticEvent(sequence = 2))
            sink.emit(first)
            sink.emit(diagnosticEvent({ TraceLineage.Linked(first.trace, first.origin, first.origin, false) }, sequence = 3))
            val spans = fixture.spans()
            assertNotEquals(spans[0].spanId, spans[2].spanId)
            assertEquals(spans[2].spanId, spans[3].parentSpanId)
            assertEquals(false, spans[3].attributes.get(parentMissing))
        }
    }

    @Test
    fun readingParentDoesNotRefreshItsInsertionPosition() {
        withTelemetry { fixture ->
            val sink = OpenTelemetryDiagnosticSink(fixture.tracer, capacity = 3)
            val first = diagnosticEvent(sequence = 1)
            sink.emit(first)
            sink.emit(diagnosticEvent(sequence = 2))
            sink.emit(diagnosticEvent({ TraceLineage.Linked(first.trace, first.origin, first.origin, false) }, sequence = 3))
            sink.emit(diagnosticEvent(sequence = 4))
            sink.emit(diagnosticEvent({ TraceLineage.Linked(first.trace, first.origin, first.origin, false) }, sequence = 5))
            val spans = fixture.spans()
            assertEquals(spans.first().spanId, spans[2].parentSpanId)
            assertEquals(false, spans[2].attributes.get(parentMissing))
            assertEquals(true, spans.last().attributes.get(parentMissing))
        }
    }

    @Test
    fun replacingOriginDoesNotRefreshItsInsertionPosition() {
        withTelemetry { fixture ->
            val sink = OpenTelemetryDiagnosticSink(fixture.tracer, capacity = 2)
            val first = diagnosticEvent(sequence = 1)
            sink.emit(first)
            sink.emit(diagnosticEvent(sequence = 2))
            sink.emit(first)
            sink.emit(diagnosticEvent(sequence = 3))
            sink.emit(diagnosticEvent({ TraceLineage.Linked(first.trace, first.origin, first.origin, false) }, sequence = 4))
            val child = fixture.spans().last()
            assertEquals(true, child.attributes.get(parentMissing))
            assertEquals(Span.getInvalid().spanContext.spanId, child.parentSpanId)
        }
    }

    @Test
    fun explicitParentSelectionIgnoresTheCurrentThreadContext() {
        withTelemetry { fixture ->
            val sink = OpenTelemetryDiagnosticSink(fixture.tracer)
            val ambient: Span = fixture.tracer.spanBuilder("ambient").startSpan()
            try {
                val scope: Scope = ambient.makeCurrent()
                try {
                    val first = diagnosticEvent(sequence = 1)
                    sink.emit(first)
                    val absent = TraceOrigin(StoreInstanceId("absent"), InputId(99), 99)
                    sink.emit(diagnosticEvent({ TraceLineage.Linked(first.trace, absent, absent, true) }, sequence = 2))
                    sink.emit(diagnosticEvent({ TraceLineage.Linked(first.trace, first.origin, first.origin, false) }, sequence = 3))
                } finally {
                    scope.close()
                }
                val spans = fixture.spans()
                assertEquals(3, spans.size)
                assertEquals(Span.getInvalid().spanContext.spanId, spans[0].parentSpanId)
                assertEquals(Span.getInvalid().spanContext.spanId, spans[1].parentSpanId)
                assertEquals(spans[0].spanId, spans[2].parentSpanId)
                assertEquals(listOf(false, true, false), spans.map { checkNotNull(it.attributes.get(parentMissing)) })
            } finally {
                ambient.end()
            }
        }
    }

    @Test
    fun failureDuringSpanExportEndsTheSpanAndDoesNotRegisterItsContext() {
        withTelemetry { fixture ->
            val failure = IllegalStateException("export failed")
            val tracer = FailingEventTracer(fixture.tracer, failure)
            val sink = OpenTelemetryDiagnosticSink(tracer)
            val first = diagnosticEvent()
            assertSame(failure, assertFailsWith<IllegalStateException> { sink.emit(first) })
            assertEquals(1, fixture.spans().size, "the failed span was ended")
            sink.emit(diagnosticChild(first.origin))
            val spans = fixture.spans()
            assertEquals(2, spans.size)
            assertEquals(true, spans.last().attributes.get(parentMissing))
            assertEquals(Span.getInvalid().spanContext.spanId, spans.last().parentSpanId)
        }
    }
}

private val parentMissing: AttributeKey<Boolean> = AttributeKey.booleanKey("actron.otel.parent.missing")

private class TelemetryFixture {
    val exporter: InMemorySpanExporter = checkNotNull(InMemorySpanExporter.create())
    val provider: SdkTracerProvider =
        checkNotNull(
            SdkTracerProvider
                .builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build(),
        )
    val tracer: Tracer = provider.get("contract-test")

    fun spans(): List<SpanData> = checkNotNull(exporter.finishedSpanItems).filterNotNull()
}

private fun withTelemetry(test: (TelemetryFixture) -> Unit) {
    val fixture = TelemetryFixture()
    try {
        test(fixture)
    } finally {
        fixture.provider.close()
    }
}

private class FailingEventTracer(
    private val delegate: Tracer,
    private val failure: IllegalStateException,
) : Tracer by delegate {
    private var failNext = true

    override fun spanBuilder(spanName: String): SpanBuilder {
        val builder: SpanBuilder = delegate.spanBuilder(spanName)
        return object : SpanBuilder by builder {
            override fun startSpan(): Span {
                val span: Span = builder.startSpan()
                if (failNext) {
                    failNext = false
                    return object : Span by span {
                        override fun addEvent(
                            name: String,
                            attributes: Attributes,
                        ): Span = throw failure
                    }
                }
                return span
            }
        }
    }
}

private class PresetSpansTracer(
    private val delegate: Tracer,
    spans: List<Span>,
) : Tracer by delegate {
    private val pending = spans.iterator()

    override fun spanBuilder(spanName: String): SpanBuilder {
        val builder: SpanBuilder = delegate.spanBuilder(spanName)
        return object : SpanBuilder by builder {
            override fun startSpan(): Span {
                if (pending.hasNext()) return pending.next()
                return builder.startSpan()
            }
        }
    }
}

private fun unsampledParentContext(): SpanContext =
    SpanContext.create(
        "0123456789abcdef0123456789abcdef",
        "0123456789abcdef",
        TraceFlags.getDefault(),
        TraceState.getDefault(),
    )
