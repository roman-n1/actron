package actron.observability.file

import actron.observability.FailureDescriptor
import actron.observability.FailureDetails
import actron.observability.FailureRelation
import actron.observability.Payload

/** Payload representations and the sanitized failure graph, shared by every entry family. */
internal object JournalPayloadCodec {
    private const val PAYLOAD_OMITTED = 0
    private const val PAYLOAD_UNAVAILABLE = 1
    private const val PAYLOAD_PROJECTED = 2
    private const val PAYLOAD_DESCRIBED = 3

    fun ByteWriter.payload(payload: Payload<*>) {
        when (payload) {
            Payload.Omitted -> {
                u8(PAYLOAD_OMITTED)
            }

            Payload.Unavailable -> {
                u8(PAYLOAD_UNAVAILABLE)
            }

            is Payload.Projected -> {
                u8(PAYLOAD_PROJECTED)
                string(payload.label)
                list(payload.fields.entries.toList()) {
                    string(it.key)
                    string(it.value)
                }
            }

            is Payload.Described -> {
                u8(PAYLOAD_DESCRIBED)
                string(payload.text)
            }

            is Payload.Retained -> {
                // The policy decided the object may be shown; a file keeps its text, not the object.
                u8(PAYLOAD_DESCRIBED)
                string(payload.value.toString())
            }
        }
    }

    fun ByteReader.payload(): Payload<Nothing> =
        when (val kind = u8()) {
            PAYLOAD_OMITTED -> Payload.Omitted
            PAYLOAD_UNAVAILABLE -> Payload.Unavailable
            PAYLOAD_PROJECTED -> Payload.Projected(string(), list { string() to string() }.toMap())
            PAYLOAD_DESCRIBED -> Payload.Described(string())
            else -> throw ByteReader.Malformed("unknown payload kind $kind")
        }

    fun ByteWriter.failure(failure: FailureDescriptor) {
        bool("type" in failure.details.attributes)
        failure.withType { string(it) }
        bool("message" in failure.details.attributes)
        failure.withMessage { string(it) }
        bool(failure.hasCause)
        failure.withCause { failure(it) }
        list(failure.suppressed) { failure(it) }
    }

    fun ByteReader.failure(): FailureDescriptor {
        var details: FailureDetails = FailureDetails.Redacted
        if (bool()) details = FailureDetails.MetadataOnly(string())
        if (bool()) {
            val message = string()
            val named = details
            details = if (named is FailureDetails.MetadataOnly) FailureDetails.Detailed(named.type, message) else FailureDetails.AnonymousMessage(message)
        }
        val related =
            buildList {
                if (bool()) add(FailureRelation.Cause(failure()))
                list { failure() }.forEach { add(FailureRelation.Suppressed(it)) }
            }
        return FailureDescriptor(details, related)
    }
}
