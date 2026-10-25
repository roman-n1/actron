package actron.observability.file

import actron.core.InputAttribution
import actron.core.InputId
import actron.observability.DiscardDescriptor
import actron.observability.DiscardKind
import actron.observability.InputDescriptor
import actron.observability.OutcomeDescriptor
import actron.observability.OutcomeKind
import actron.observability.file.JournalPayloadCodec.failure
import actron.observability.file.JournalPayloadCodec.payload

/** Store input identity, origin and terminal outcome on the wire. */
internal object JournalInputCodec {
    fun ByteWriter.attribution(input: InputAttribution) {
        bool(input is InputId)
        input.correlate { i64(it.value) }
    }

    fun ByteReader.attribution(): InputAttribution = if (bool()) InputId(i64()) else InputAttribution.Unattributed

    fun ByteWriter.input(kind: InputDescriptor<*>) {
        when (kind) {
            InputDescriptor.Startup -> {
                u8(1)
            }

            is InputDescriptor.Dispatch -> {
                u8(2)
                payload(kind.action)
            }

            is InputDescriptor.Transaction -> {
                u8(3)
                attribution(kind.origin)
            }

            is InputDescriptor.Recovery -> {
                u8(4)
                failure(kind.failure)
                attribution(kind.origin)
            }
        }
    }

    fun ByteReader.input(): InputDescriptor<Nothing> =
        when (val kind = u8()) {
            1 -> InputDescriptor.Startup
            2 -> InputDescriptor.Dispatch(payload())
            3 -> InputDescriptor.Transaction(attribution())
            4 -> InputDescriptor.Recovery(failure(), attribution())
            else -> throw ByteReader.Malformed("unknown input kind $kind")
        }

    fun ByteReader.discard(): DiscardDescriptor {
        val kind = enumNamed<DiscardKind>(string())
        return if (bool()) DiscardDescriptor(kind, failure()) else DiscardDescriptor(kind)
    }

    fun ByteReader.outcome(): OutcomeDescriptor {
        val kind = enumNamed<OutcomeKind>(string())
        val commits = i32()
        return if (bool()) OutcomeDescriptor(kind, commits, failure()) else OutcomeDescriptor(kind, commits)
    }
}
