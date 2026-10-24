package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.ExecutorCheckpoint
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Version of the JSON form of a [Recording]. Bumped when a field or a value changes meaning; a
 * reader that meets a newer version refuses, an older one is migrated by an explicit
 * [FormatMigration] or refused, never filled in with defaults.
 *
 * History: 1 began at an `initial` snapshot; 2 begins at a `start` checkpoint of the executor
 * (snapshot, clock, commands running, queued and ending); 3 adds the `bridgeReceived` input
 * with its message; 4 adds the pending `effects` of the mailbox to the checkpoint; 5 adds the
 * `external` input with its source. The codec migrates each version to the next itself.
 */
const val RECORDING_FORMAT_VERSION: Int = 6

/**
 * Turns the JSON of one format version into the next: an explicit, testable step.
 */
class FormatMigration(
    val from: Int,
    val to: Int,
    val migrate: (JsonObject) -> JsonObject,
) {
    init {
        require(to > from) { "[Actron] A migration goes forward: $from -> $to" }
    }
}

/**
 * What [RecordingCodec.decode] found.
 */
sealed interface DecodedRecording<C : Any, A : Action, CMD : Any, E : Event> {
    /** The recording, exactly as encoded. */
    data class Decoded<C : Any, A : Action, CMD : Any, E : Event>(
        val recording: Recording<C, A, CMD, E>,
    ) : DecodedRecording<C, A, CMD, E>

    /** The format version is unknown to this codec and no migration leads to a known one. */
    data class Unsupported<C : Any, A : Action, CMD : Any, E : Event>(
        val formatVersion: Int,
        val reason: String,
    ) : DecodedRecording<C, A, CMD, E>

    /** The text is not a recording of this format, or a payload could not be decoded; [at] says where. */
    data class Invalid<C : Any, A : Action, CMD : Any, E : Event>(
        val reason: String,
        val location: RecordingLocation = RecordingLocation.Document,
    ) : DecodedRecording<C, A, CMD, E> {
        constructor(reason: String, at: String) : this(reason, RecordingLocation.Part(at))
    }
}

/** Scope of a decoder failure: the document itself or an actual named part of it. */
sealed interface RecordingLocation {
    fun withPart(accept: (String) -> Unit)

    data object Document : RecordingLocation {
        override fun withPart(accept: (String) -> Unit) = Unit
    }

    data class Part(
        val path: String,
    ) : RecordingLocation {
        override fun withPart(accept: (String) -> Unit) = accept(path)
    }
}

/**
 * Encodes a [Recording] as JSON and decodes it back, with the application's serializers for its
 * own types. The structure (the start checkpoint, snapshots, inputs, decisions, ids, timers,
 * failures) is the codec's and versioned by [RECORDING_FORMAT_VERSION]; the context, actions,
 * commands and events are whatever the serializers write. The output is canonical: fields in a
 * fixed order, maps sorted by key, commands sorted by id, no defaults, no nulls, durations in
 * ISO-8601, so equal recordings encode to equal text and the text can be a golden fixture.
 *
 * A decoded recording equals the encoded one when the serializers round-trip their values; an
 * older format is migrated by the codec's own migrations and the [migrations] given, applied in
 * sequence until the current version is reached; a newer or an unknown one is
 * [DecodedRecording.Unsupported]; a malformed text, an inconsistent checkpoint or a payload the
 * serializers reject is [DecodedRecording.Invalid] with the position.
 *
 * @param context Serializer of the machine's context
 * @param action Serializer of the actions (dispatched and returned by commands)
 * @param command Serializer of the commands
 * @param event Serializer of the events
 * @param migrations Migrations from format versions the codec does not migrate itself
 */
class RecordingCodec<C : Any, A : Action, CMD : Any, E : Event>(
    private val context: KSerializer<C>,
    private val action: KSerializer<A>,
    private val command: KSerializer<CMD>,
    private val event: KSerializer<E>,
    private val migrations: List<FormatMigration> = emptyList(),
) {
    private val json =
        Json {
            encodeDefaults = false
            explicitNulls = false
            prettyPrint = false
            ignoreUnknownKeys = false
        }

    private val builtIn = builtInRecordingMigrations()
    private val writer = RecordingWireEncoder(json, context, action, command, event)
    private val reader = RecordingWireReader(json, context, action, command, event)

    /**
     * The canonical JSON of [recording].
     */
    fun encode(recording: Recording<C, A, CMD, E>): String = json.encodeToString(RecordingWire.serializer(), with(writer) { recording.toWire() })

    /**
     * The recording in [text], migrated to the current format when older.
     */
    fun decode(text: String): DecodedRecording<C, A, CMD, E> {
        var element: JsonObject =
            try {
                json.parseToJsonElement(text) as? JsonObject ?: return DecodedRecording.Invalid("not a JSON object")
            } catch (e: SerializationException) {
                return DecodedRecording.Invalid("not JSON: ${e.message}")
            }
        var formatVersion =
            (element["formatVersion"] as? JsonPrimitive)?.intOrNull
                ?: return DecodedRecording.Invalid("no formatVersion")
        if (formatVersion > RECORDING_FORMAT_VERSION) {
            return DecodedRecording.Unsupported(formatVersion, "format $formatVersion is newer than this codec's $RECORDING_FORMAT_VERSION")
        }
        while (formatVersion < RECORDING_FORMAT_VERSION) {
            val migration =
                (builtIn + migrations).firstOrNull { it.from == formatVersion }
                    ?: return DecodedRecording.Unsupported(formatVersion, "no migration from format $formatVersion")
            element = migration.migrate(element)
            formatVersion = migration.to
        }
        val wire =
            try {
                json.decodeFromJsonElement(RecordingWire.serializer(), element)
            } catch (e: SerializationException) {
                return DecodedRecording.Invalid("not a recording of format $RECORDING_FORMAT_VERSION: ${e.message}")
            } catch (e: IllegalArgumentException) {
                return DecodedRecording.Invalid("not a recording of format $RECORDING_FORMAT_VERSION: ${e.message}")
            }
        if (wire.formatVersion != RECORDING_FORMAT_VERSION) return DecodedRecording.Unsupported(wire.formatVersion, "migrated to ${wire.formatVersion}, not to $RECORDING_FORMAT_VERSION")
        return try {
            DecodedRecording.Decoded(reader.readRecording(wire))
        } catch (e: DecodeFailure) {
            DecodedRecording.Invalid(e.message ?: "invalid", e.at)
        } catch (e: IllegalArgumentException) {
            DecodedRecording.Invalid(e.message ?: "invalid")
        }
    }

    // --- parts, for the segments of a recording file ---

    /** The JSON of [checkpoint] alone. */
    fun encodeCheckpoint(checkpoint: ExecutorCheckpoint<C, CMD>): String = json.encodeToString(CheckpointWire.serializer(), with(writer) { checkpoint.toWire() })

    /**
     * The checkpoint [encodeCheckpoint] wrote, for a recording of [definition] and [version].
     *
     * @throws IllegalArgumentException if [text] is not one, with where it failed
     */
    fun decodeCheckpoint(
        text: String,
        definition: DefinitionId,
        version: DefinitionVersion,
    ): ExecutorCheckpoint<C, CMD> = part("checkpoint") { with(reader) { json.decodeFromString(CheckpointWire.serializer(), text).toCheckpoint(definition, version, "checkpoint") } }

    /** The JSON of [step] alone. */
    fun encodeStep(step: RecordedStep<C, A, CMD, E>): String = json.encodeToString(StepWire.serializer(), with(writer) { step.toWire() })

    /**
     * The step [encodeStep] wrote, for a recording of [definition] and [version].
     *
     * @throws IllegalArgumentException if [text] is not one, with where it failed
     */
    fun decodeStep(
        text: String,
        definition: DefinitionId,
        version: DefinitionVersion,
    ): RecordedStep<C, A, CMD, E> = part("step") { with(reader) { json.decodeFromString(StepWire.serializer(), text).toStep(definition, version, "step") } }

    private inline fun <T : Any> part(
        what: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: SerializationException) {
            throw IllegalArgumentException("not a $what of format $RECORDING_FORMAT_VERSION: ${e.message}")
        } catch (e: DecodeFailure) {
            throw IllegalArgumentException("${e.message} (at ${e.at})")
        }
}

internal class DecodeFailure : RuntimeException {
    val at: String

    constructor(message: String, at: String) : super(message) {
        this.at = at
    }

    constructor(message: String, at: String, cause: Throwable) : super(message, cause) {
        this.at = at
    }
}
