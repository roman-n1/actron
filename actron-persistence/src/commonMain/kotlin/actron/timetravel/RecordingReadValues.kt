package actron.timetravel

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.time.Duration

internal inline fun <T : Any> decoding(
    at: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: DecodeFailure) {
        throw e
    } catch (e: SerializationException) {
        throw DecodeFailure(e.message ?: "cannot decode", at, e)
    } catch (e: IllegalArgumentException) {
        throw DecodeFailure(e.message ?: "cannot decode", at, e)
    }

internal fun JsonElement.required(
    message: String,
    at: String,
): JsonElement {
    if (this == JsonNull) throw DecodeFailure(message, at)
    return this
}

internal fun JsonElement.text(
    at: String,
    json: Json,
): String =
    decoding(at) {
        json.decodeFromJsonElement(String.serializer(), this)
    }

internal fun JsonElement.integer(
    at: String,
    json: Json,
): Int =
    decoding(at) {
        json.decodeFromJsonElement(Int.serializer(), this)
    }

internal fun JsonElement.long(
    at: String,
    json: Json,
): Long =
    decoding(at) {
        json.decodeFromJsonElement(Long.serializer(), this)
    }

internal fun String.toDuration(at: String): Duration =
    try {
        Duration.parseIsoString(this)
    } catch (e: IllegalArgumentException) {
        throw DecodeFailure("not an ISO-8601 duration: '$this'", at, e)
    }

internal fun String.toLongOrThrow(at: String): Long = toLongOrNull() ?: throw DecodeFailure("not an id: '$this'", at)
