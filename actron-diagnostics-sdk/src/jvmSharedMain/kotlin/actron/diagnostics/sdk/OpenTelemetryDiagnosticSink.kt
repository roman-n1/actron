package actron.diagnostics.sdk

import actron.diagnostics.CausalTraceEvent
import actron.diagnostics.DiagnosticSink
import actron.diagnostics.attributes
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context

/**
 * Decision receipt spans, not command IO durations. No thread-local context or SDK initialization.
 *
 * Parent contexts are retained per sink in FIFO insertion order. Reading or replacing an origin
 * does not refresh its position. A linked event with an absent, evicted or invalid SDK parent
 * starts a root span with `actron.otel.parent.missing=true`. Valid unsampled contexts remain usable.
 *
 * @param capacity positive maximum number of retained parent contexts, shared across all Stores
 * using this sink; defaults to 100. This limits parent retention, not exported spans or SDK queues.
 */
class OpenTelemetryDiagnosticSink(
    private val tracer: Tracer,
    private val capacity: Int = 100,
) : DiagnosticSink {
    init {
        require(capacity > 0)
    }

    private val parents = SpanParentContexts(capacity)

    override fun emit(event: CausalTraceEvent) {
        val parent = parents.resolve(event.parent)
        val builder: SpanBuilder = tracer.spanBuilder("actron.${event.inputKind}")
        parent.configure(builder)
        val attributes: AttributesBuilder = Attributes.builder()
        event.attributes().forEach { (key, value) -> attributes.put(key, value) }
        attributes.put("actron.otel.parent.missing", parent is SpanParentage.Missing)
        val span: Span = builder.setAllAttributes(attributes.build()).startSpan()
        try {
            event.transitions.forEach { span.addEvent("transition", Attributes.builder().put("actron.transition", it.index.toLong()).build()) }
            event.commands.forEach { span.addEvent("command.registered", Attributes.builder().put("actron.command", it.value).build()) }
            if (event.assessment.faults.isNotEmpty()) {
                span.setStatus(StatusCode.ERROR)
                event.withFailureType { span.setStatus(StatusCode.ERROR, it) }
            }
            parents.remember(event.origin) {
                val context: SpanContext = span.spanContext
                context
            }
        } finally {
            span.end()
        }
    }

    private fun SpanParentage.configure(builder: SpanBuilder) {
        when (this) {
            SpanParentage.Root, SpanParentage.Missing -> builder.setNoParent()
            is SpanParentage.Linked -> builder.setParent(Context.root().with(Span.wrap(context)))
        }
    }
}
