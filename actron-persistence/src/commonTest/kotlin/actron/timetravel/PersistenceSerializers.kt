package actron.timetravel

import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

internal fun <T : Any> KSerializer<T>.recordPersistenceCalls(
    name: String,
    calls: MutableList<String>,
): KSerializer<T> {
    val delegate = this
    return object : KSerializer<T> by delegate {
        override fun serialize(
            encoder: Encoder,
            value: T,
        ) {
            calls += "encode:$name"
            delegate.serialize(encoder, value)
        }

        override fun deserialize(decoder: Decoder): T {
            calls += "decode:$name"
            return delegate.deserialize(decoder)
        }
    }
}
