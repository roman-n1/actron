package actron.timetravel.file

import actron.observability.MachineGroupId
import actron.observability.StoreInstanceId
import actron.observability.file.Framing
import actron.observability.file.SegmentEnding
import actron.observability.file.SegmentMark
import actron.observability.file.SegmentStorage
import actron.observability.file.beforeHeader
import actron.statechart.machine.EffectId
import actron.statechart.machine.MessageId
import actron.statechart.machine.PairRole
import actron.statechart.machine.RoutePair
import actron.statechart.machine.RouteProtocol
import actron.statechart.machine.SourceId
import actron.statechart.machine.SourceSnapshot
import actron.timetravel.GroupRecording
import actron.timetravel.GroupRoute
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The layout of a segment of a group's order file: `KOMAGRPO`, a header frame (the group, its
 * members, the bridge's routes, the attached sources, the index of the first entry, the
 * messages in flight before it and, when the segment begins at a cut, the cut: the members'
 * step counts and the sources' snapshots there), entry frames (which member decided which
 * step, the messages the step sent and the one it received), and the end frame. With the
 * members' recording files ([RecordingFileFormat]) it is the group container:
 * [GroupRecordingFiles] reads them back into a [GroupRecording] over the range every file
 * still covers.
 *
 * Format 2 added the cut to the header; a format 1 segment reads as one without cuts. Format 3
 * added the pair of a route; a format 2 segment reads as routes without pairs.
 */
object GroupRecordingFileFormat {
    val MAGIC: ByteArray = "KOMAGRPO".encodeToByteArray()

    const val EXTENSION: String = ".group"

    /** The version of this layout, written into every header. */
    const val VERSION: Int = 3

    private const val TAG_HEADER = 1
    private const val TAG_ENTRY = 2

    private val json =
        Json {
            encodeDefaults = false
            explicitNulls = false
            ignoreUnknownKeys = false
        }

    fun segmentName(
        group: MachineGroupId,
        index: Int,
    ): String = "${group.value}-${index.toString().padStart(6, '0')}$EXTENSION"

    fun parseSegmentName(
        name: String,
        accept: (MachineGroupId, Int) -> Unit,
    ): Boolean {
        if (!name.endsWith(EXTENSION)) return false
        val stem = name.removeSuffix(EXTENSION)
        val dash = stem.lastIndexOf('-')
        if (dash <= 0) return false
        val index = stem.substring(dash + 1).takeIf { it.length == 6 }?.toIntOrNull() ?: return false
        accept(MachineGroupId(stem.substring(0, dash)), index)
        return true
    }

    internal fun namedSegments(
        storage: SegmentStorage,
        owner: MachineGroupId,
    ): List<Pair<Int, String>> =
        buildList {
            for (info in storage.list()) {
                parseSegmentName(info.name) { id, index ->
                    if (id == owner) add(index to info.name)
                }
            }
        }

    fun header(header: GroupSegmentHeader): ByteArray {
        val wire =
            GroupHeaderWire(
                header.fileFormatVersion,
                header.group.value,
                header.members.map { it.value },
                header.routes.map {
                    val pair = it.pair
                    if (pair is RoutePair) RouteWire(it.from.value, it.to.value, JsonPrimitive(pair.name), JsonPrimitive(pair.role.name)) else RouteWire(it.from.value, it.to.value)
                },
                header.sourceIds.map { it.value },
                header.index,
                header.firstEntry,
                header.inFlight.map { MessageWireG(it.from.value, it.effect.value) },
                cutNode(header.boundary),
            )
        return MAGIC + Framing.frame(byteArrayOf(TAG_HEADER.toByte()) + json.encodeToString(GroupHeaderWire.serializer(), wire).encodeToByteArray())
    }

    private fun cutNode(boundary: GroupBoundary): JsonElement {
        var node: JsonElement = JsonNull
        boundary.withCut { cut ->
            node =
                json.encodeToJsonElement(
                    CutWire.serializer(),
                    CutWire(
                        cut.counts.map { (store, count) -> store.value to count }.toMap(),
                        cut.sources.values.map { SourceSnapshotWire(it.source.value, it.kind, it.version, it.fields) },
                    ),
                )
        }
        return node
    }

    fun entryFrame(entry: GroupOrderEntry): ByteArray {
        var received: JsonElement = JsonNull
        entry.delivery.withReceived { received = json.encodeToJsonElement(MessageWireG.serializer(), MessageWireG(it.from.value, it.effect.value)) }
        val wire = EntryWire(entry.store.value, entry.step, entry.sent.map { MessageWireG(it.from.value, it.effect.value) }, received)
        return Framing.frame(byteArrayOf(TAG_ENTRY.toByte()) + json.encodeToString(EntryWire.serializer(), wire).encodeToByteArray())
    }

    fun decodeSegment(
        name: String,
        bytes: ByteArray,
    ): DecodedGroupSegment {
        val read = Framing.read(name, bytes, MAGIC)
        val frames = read.frames
        if (frames.isEmpty()) {
            val mark = read.ending.beforeHeader(name, MAGIC.size)
            return DecodedGroupSegment.Unreadable(mark)
        }
        val header =
            try {
                val payload = frames[0].payload
                require(payload.isNotEmpty() && payload[0].toInt() == TAG_HEADER) { "not a header frame" }
                val wire = json.decodeFromString(GroupHeaderWire.serializer(), payload.decodeToString(1))
                wire.toHeader()
            } catch (e: SerializationException) {
                return DecodedGroupSegment.Unreadable(SegmentMark.Corrupt(name, frames[0].offset, 0, "header: ${e.message}"))
            } catch (e: IllegalArgumentException) {
                return DecodedGroupSegment.Unreadable(SegmentMark.Corrupt(name, frames[0].offset, 0, "header: ${e.message}"))
            }
        if (header.fileFormatVersion != VERSION) return DecodedGroupSegment.Readable(header, emptyList(), SegmentEnding.Stopped(SegmentMark.UnsupportedFormat(name, header.fileFormatVersion, 0)))
        val entries = mutableListOf<GroupOrderEntry>()
        for (frame in frames.drop(1)) {
            val entry =
                try {
                    val payload = frame.payload
                    require(payload.isNotEmpty() && payload[0].toInt() == TAG_ENTRY) { "not an entry frame" }
                    val wire = json.decodeFromString(EntryWire.serializer(), payload.decodeToString(1))
                    wire.toEntry()
                } catch (e: SerializationException) {
                    return DecodedGroupSegment.Readable(header, entries, SegmentEnding.Stopped(SegmentMark.Corrupt(name, frame.offset, entries.size, "entry: ${e.message}")))
                } catch (e: IllegalArgumentException) {
                    return DecodedGroupSegment.Readable(header, entries, SegmentEnding.Stopped(SegmentMark.Corrupt(name, frame.offset, entries.size, "entry: ${e.message}")))
                }
            entries += entry
        }
        return DecodedGroupSegment.Readable(header, entries, read.ending.countRecords(entries.size))
    }

    private fun GroupHeaderWire.toHeader(): GroupSegmentHeader =
        GroupSegmentHeader(
            this.formatVersion,
            MachineGroupId(this.group),
            this.members.map(::StoreInstanceId),
            this.routes.map { GroupRoute(StoreInstanceId(it.from), StoreInstanceId(it.to), it.pair()) },
            this.sourceIds.map(::SourceId).toSet(),
            this.index,
            this.firstEntry,
            this.inFlight.map { MessageId(StoreInstanceId(it.from), EffectId(it.effect)) },
            if (this.cut == JsonNull) {
                GroupBoundary.Continuation
            } else {
                json.decodeFromJsonElement(CutWire.serializer(), this.cut).let { cut ->
                    RecordedCut(
                        this.firstEntry,
                        cut.counts.map { (store, count) -> StoreInstanceId(store) to count }.toMap(),
                        cut.sources.associate { SourceId(it.source) to SourceSnapshot(SourceId(it.source), it.kind, it.version, it.fields) },
                    )
                }
            },
        )

    private fun EntryWire.toEntry(): GroupOrderEntry =
        GroupOrderEntry(
            StoreInstanceId(store),
            step,
            sent.map { MessageId(StoreInstanceId(it.from), EffectId(it.effect)) },
            if (received == JsonNull) {
                GroupDelivery.Local
            } else {
                val message = json.decodeFromJsonElement(MessageWireG.serializer(), received)
                GroupDelivery.Bridged(MessageId(StoreInstanceId(message.from), EffectId(message.effect)))
            },
        )
}

@Serializable
internal class GroupHeaderWire(
    val formatVersion: Int,
    val group: String,
    val members: List<String>,
    val routes: List<RouteWire>,
    val sourceIds: List<String>,
    val index: Int,
    val firstEntry: Int,
    val inFlight: List<MessageWireG> = emptyList(),
    val cut: JsonElement = JsonNull,
)

@Serializable
internal class CutWire(
    val counts: Map<String, Int>,
    val sources: List<SourceSnapshotWire>,
)

@Serializable
internal class SourceSnapshotWire(
    val source: String,
    val kind: String,
    val version: Int,
    val fields: Map<String, String>,
)

@Serializable
internal class RouteWire(
    val from: String,
    val to: String,
    val pair: JsonElement = JsonNull,
    val role: JsonElement = JsonNull,
) {
    fun pair(): RouteProtocol {
        require(pair == JsonNull || (pair is JsonPrimitive && pair.isString)) { "[Actron] Route pair must be a JSON string" }
        require(role == JsonNull || (role is JsonPrimitive && role.isString)) { "[Actron] Route role must be a JSON string" }
        return if (pair != JsonNull && role != JsonNull) RoutePair(pair.jsonPrimitive.content, PairRole.valueOf(role.jsonPrimitive.content)) else RouteProtocol.OneWay
    }
}

@Serializable
internal class MessageWireG(
    val from: String,
    val effect: Long,
)

@Serializable
internal class EntryWire(
    val store: String,
    val step: Int,
    val sent: List<MessageWireG> = emptyList(),
    val received: JsonElement = JsonNull,
)
