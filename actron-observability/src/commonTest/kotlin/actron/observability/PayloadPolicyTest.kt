@file:OptIn(ExperimentalActronApi::class)

package actron.observability

import actron.core.ExperimentalActronApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PayloadPolicyTest {
    /** An exception whose cause can be set after construction, to build a cycle in common code. */
    private class Cyclic(
        message: String,
    ) : Exception(message) {
        var next: Throwable = this
        override val cause: Throwable get() = next
    }

    @Test
    fun failureDescriptor_keepsTypesAndCausality_andWithholdsMessagesByDefault() {
        val cause = IllegalArgumentException("inner secret")
        val error = IllegalStateException("outer secret", cause)
        error.addSuppressed(ArithmeticException("suppressed secret"))

        val descriptor = FailureDescriptor.of(error)

        assertEquals("IllegalStateException", descriptor.type)
        kotlin.test.assertFalse("message" in descriptor.details.attributes)
        assertEquals("IllegalArgumentException", descriptor.cause.type)
        kotlin.test.assertFalse("message" in descriptor.cause.details.attributes)
        assertEquals(listOf("ArithmeticException"), descriptor.suppressed.map { it.type })
        assertEquals("IllegalStateException <- IllegalArgumentException +1 suppressed", JournalFormat.failure(descriptor))
    }

    @Test
    fun failureDescriptor_includesMessagesOnlyWhenAsked() {
        val error = IllegalStateException("shown", IllegalArgumentException("also shown"))

        val descriptor = FailureDescriptor.of(error, includeMessages = true)

        assertEquals("shown", descriptor.message)
        assertEquals("also shown", descriptor.cause.message)
        assertEquals("IllegalStateException: shown <- IllegalArgumentException: also shown", JournalFormat.failure(descriptor))
    }

    @Test
    fun failureDescriptor_stopsAtACycle() {
        val first = Cyclic("first")
        val second = Cyclic("second")
        first.next = second
        second.next = first

        val descriptor = FailureDescriptor.of(first, includeMessages = true)

        assertEquals("first", descriptor.message)
        assertEquals("second", descriptor.cause.message)
        kotlin.test.assertFalse(descriptor.cause.hasCause, "the cycle back to the first error is not followed")
    }

    @Test
    fun failureDescriptor_stopsAtTheDepthLimit() {
        var deep: Throwable = IllegalStateException("leaf")
        repeat(20) { deep = IllegalStateException("level", deep) }

        fun depthOf(error: FailureDescriptor): Int {
            var depth = 1
            error.withCause { depth += depthOf(it) }
            return depth
        }
        val depth = depthOf(FailureDescriptor.of(deep, maxDepth = 3))

        assertEquals(4, depth, "the error and three causes")
    }

    @Test
    fun metadataOnly_omitsEveryPayload() {
        val policy = PayloadPolicy.metadataOnly<actron.core.State, actron.core.Action, actron.core.Event>()
        val state =
            object : actron.core.State {
                override fun toString(): String = "secret"
            }

        assertEquals(Payload.Omitted, policy.state(state))
        assertEquals(Payload.Omitted, policy.action(object : actron.core.Action {}))
        assertEquals(Payload.Omitted, policy.event(object : actron.core.Event {}))
        assertEquals("-", JournalFormat.payload(policy.state(state)))
    }

    @Test
    fun retainAll_keepsTheObjects() {
        val policy = PayloadPolicy.retainAll<actron.core.State, actron.core.Action, actron.core.Event>()
        val state =
            object : actron.core.State {
                override fun toString(): String = "visible"
            }

        assertEquals(Payload.Retained(state), policy.state(state))
        assertEquals("visible", JournalFormat.payload(policy.state(state)))
        assertEquals("IllegalStateException: shown", JournalFormat.failure(policy.failure(IllegalStateException("shown"))))
    }

    @Test
    fun projectedPayload_printsItsLabelAndFields() {
        assertEquals("Ready", JournalFormat.payload(Payload.Projected("Ready")))
        assertEquals("Ready(count=1, query=***)", JournalFormat.payload(Payload.Projected("Ready", mapOf("count" to "1", "query" to "***"))))
        assertEquals("?", JournalFormat.payload(Payload.Unavailable))
    }
}

// These projections require the attribute/cause that the test has established.
private val FailureDescriptor.type: String get() = details.attributes.getValue("type")
private val FailureDescriptor.message: String get() = details.attributes.getValue("message")
private val FailureDescriptor.cause: FailureDescriptor get() =
    buildList<FailureDescriptor> {
        withCause { add(it) }
    }.single()
