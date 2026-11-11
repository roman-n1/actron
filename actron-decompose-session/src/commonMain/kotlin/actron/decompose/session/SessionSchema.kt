package actron.decompose.session

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.Machine
import actron.timetravel.MachinePersistence
import actron.timetravel.RecordingCodec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/** Generated serializers keep serialization wiring next to the machine definition. */
inline fun <reified C : Any, reified A : Action, reified CMD : Any, reified E : Event> Machine<C, A, CMD, E>.restorable(): SessionMachine<C, A, CMD, E> = SessionMachine(this, RecordingCodec(serializer<C>(), serializer<A>(), serializer<CMD>(), serializer<E>()))

/** One reusable definition; instance keys distinguish screens using the same definition. */
class SessionMachine<C : Any, A : Action, CMD : Any, E : Event>(
    val machine: Machine<C, A, CMD, E>,
    codec: RecordingCodec<C, A, CMD, E>,
) {
    internal val persistence = MachinePersistence(machine, codec)

    internal fun validate(saved: String) {
        persistence.restore(saved)
    }
}

/** Immutable encoded checkpoint. It contains data, never live components or executors. */
class SessionCheckpoint internal constructor(
    internal val document: SessionDocument,
)

/** Explicit app identity/version also protects navigation and account-specific payloads. */
internal class SessionSchema<R : Any>(
    private val identity: String,
    private val version: String,
    private val navigation: KSerializer<R>,
    machines: List<SessionMachine<*, *, *, *>>,
) {
    private val definitions = machines.associateBy { it.machine.id.value }

    init {
        require(identity.isNotBlank() && version.isNotBlank())
        require(definitions.size == machines.size) { "Duplicate session machine definition" }
    }

    fun encode(checkpoint: SessionCheckpoint): String {
        validate(checkpoint)
        return Json.encodeToString(SessionDocument.serializer(), checkpoint.document)
    }

    /** Validates every machine and navigation before any live executor is stopped. */
    fun decode(saved: String): SessionCheckpoint = SessionCheckpoint(Json.decodeFromString(SessionDocument.serializer(), saved)).also(::validate)

    internal fun validate(checkpoint: SessionCheckpoint) {
        val document = checkpoint.document
        require(document.format == 1) { "Unsupported session checkpoint format" }
        require(document.identity == identity && document.version == version) { "Incompatible session identity/version" }
        routes(checkpoint)
        document.machines.forEach { (key, saved) ->
            require(key.isNotBlank()) { "Empty session instance key" }
            require(saved.definition in definitions) { "Unknown session definition: ${saved.definition}" }
            definitions.getValue(saved.definition).validate(saved.checkpoint)
        }
    }

    internal fun requireDefinition(definition: SessionMachine<*, *, *, *>) {
        require(definition.machine.id.value in definitions) { "Machine is not registered in this session schema" }
        require(definitions.getValue(definition.machine.id.value).machine.version == definition.machine.version) { "Machine version differs from the session schema" }
    }

    internal fun checkpoint(
        routes: R,
        machines: Map<String, SavedMachine>,
    ): SessionCheckpoint = SessionCheckpoint(SessionDocument(identity = identity, version = version, navigation = Json.encodeToString(navigation, routes), machines = machines))

    internal fun routes(checkpoint: SessionCheckpoint): R = Json.decodeFromString(navigation, checkpoint.document.navigation)
}

@Serializable
internal data class SessionDocument(
    val format: Int = 1,
    val identity: String,
    val version: String,
    val navigation: String,
    val machines: Map<String, SavedMachine>,
)

@Serializable
internal data class SavedMachine(
    val definition: String,
    val checkpoint: String,
)
