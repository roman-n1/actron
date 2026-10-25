package actron.diagnostics.sdk

import actron.diagnostics.TraceOrigin
import actron.diagnostics.TraceParent
import io.opentelemetry.api.trace.SpanContext

/** Owns bounded context retention. Reads and replacements preserve the original insertion order. */
internal class SpanParentContexts(
    private val capacity: Int,
) {
    private val contexts = linkedMapOf<TraceOrigin, SpanContext>()

    fun resolve(parent: TraceParent): SpanParentage =
        synchronized(contexts) {
            var resolved: SpanParentage = if (parent.linked) SpanParentage.Missing else SpanParentage.Root
            parent.withParent { origin ->
                if (origin in contexts) resolved = SpanParentage.Linked(contexts.getValue(origin))
            }
            resolved
        }

    fun remember(
        origin: TraceOrigin,
        context: () -> SpanContext,
    ) {
        synchronized(contexts) {
            // Keep context capture and insertion in the same critical section as before extraction.
            val captured = context()
            if (captured.isValid) {
                contexts[origin] = captured
            } else {
                contexts.remove(origin)
            }
            while (contexts.size > capacity) contexts.remove(contexts.keys.first())
        }
    }
}

/** Distinguishes a genuine root from a linked event whose SDK parent is no longer available. */
internal sealed interface SpanParentage {
    data object Root : SpanParentage

    data object Missing : SpanParentage

    class Linked(
        val context: SpanContext,
    ) : SpanParentage
}
