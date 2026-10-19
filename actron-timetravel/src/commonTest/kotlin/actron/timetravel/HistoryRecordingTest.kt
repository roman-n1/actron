package actron.timetravel

import actron.statechart.*
import kotlinx.serialization.builtins.serializer
import kotlin.test.*
import kotlin.time.Instant

class HistoryRecordingTest {
    private val codec = HistoryRecordingCodec("test/1", String.serializer())

    private fun recording(): HistoryRecording<String> {
        val timeline = HistoryTimeline<String>(2)
        val machine = timeline.machine("Поиск", "search")
        repeat(4) { timeline.append(machine, 0, HistoryDetails("edit $it", "Action", "Handled", emptyList(), listOf(StateField("query", "город $it"))), "город $it", Instant.parse("2026-10-20T01:00:00Z")) }
        return timeline.recording("test/1", { it })
    }

    @Test fun roundTripRetainsTruncatedIdsUnicodeAndTimestamps() {
        val original = recording()
        val decoded = codec.decode(codec.encode(original))
        assertEquals(original, decoded)
        val detached = HistoryTimeline.fromRecording(decoded)
        assertEquals(listOf(3L, 4L), detached.entries.value.map { it.id })
        assertEquals(
            "город 3",
            detached.entries.value
                .last()
                .snapshot,
        )
        detached.clear()
        assertEquals(2, decoded.entries.size)
    }

    @Test fun formatSchemaOrderIdentityAndTimeAreValidated() {
        val original = recording()
        listOf(
            original.copy(format = 2),
            original.copy(schema = "test/2"),
            original.copy(entries = original.entries.reversed()),
            original.copy(entries = listOf(original.entries.first().copy(at = "invalid"))),
            original.copy(entries = listOf(original.entries.first(), original.entries.last().copy(machine = HistoryMachine(1, "Other", "other")))),
        ).forEach { assertFailsWith<IllegalArgumentException> { codec.encode(it) } }
        val bytes =
            codec
                .encode(original)
                .decodeToString()
                .replace("\"format\":1", "\"format\":99")
                .encodeToByteArray()
        assertFailsWith<IllegalArgumentException> { codec.decode(bytes) }
    }

    @Test fun malformedOversizedDeepAndInvalidUtf8AreRejected() {
        assertFailsWith<IllegalArgumentException> { codec.decode(byteArrayOf(0xff.toByte())) }
        assertFailsWith<IllegalArgumentException> { codec.decode("{broken".encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { codec.decode(("[".repeat(65) + "]".repeat(65)).encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { HistoryRecordingCodec("test/1", String.serializer(), maxBytes = 4).decode(codec.encode(recording())) }
        assertFailsWith<IllegalArgumentException> { HistoryRecordingCodec("test/1", String.serializer(), maxEntries = 1).decode(codec.encode(recording())) }
        val quoted = recording().copy(entries = recording().entries.map { it.copy(snapshot = "[\\\"{".repeat(100)) })
        assertEquals(quoted, codec.decode(codec.encode(quoted)))
    }

    @Test fun versionOrGraphChangeIsRejectedEvenWhenDeveloperForgetsVersionBump() {
        val idle = StateId("Idle")
        val done = StateId("Done")
        val chart = StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done)), listOf(Transition(idle, done, ActionMatcher("Go"))))
        val definition = chart.historyDefinition("search", "1")
        val originalCodec = HistoryRecordingCodec("test/1", String.serializer(), definitions = listOf(definition))
        val bytes = originalCodec.encode(recording().copy(definitions = listOf(definition)))
        val variants =
            listOf(
                definition.copy(version = "2"),
                chart.copy(transitions = emptyList()).historyDefinition("search", "1"),
                chart.copy(states = listOf(AtomicState(idle), AtomicState(done))).historyDefinition("search", "1"),
                chart.copy(initial = done).historyDefinition("search", "1"),
            )
        variants.forEach { changed ->
            assertFailsWith<IllegalArgumentException> { HistoryRecordingCodec("test/1", String.serializer(), definitions = listOf(changed)).decode(bytes) }
        }
    }

    @Test fun transitionOrderAndHierarchyAffectCompatibility() {
        val parent = StateId("Parent")
        val a = StateId("A")
        val b = StateId("B")
        val chart = StateChartDefinition(parent, listOf(CompoundState(parent, a), AtomicState(a, parent), AtomicState(b, parent)), listOf(Transition(a, b, ActionMatcher("Next")), Transition(b, a, ActionMatcher("Back"))))
        assertNotEquals(chart.historyDefinition("m", "1"), chart.copy(transitions = chart.transitions.reversed()).historyDefinition("m", "1"))
        assertNotEquals(chart.historyDefinition("m", "1"), chart.copy(states = chart.states + FinalState(StateId("Done"))).historyDefinition("m", "1"))
    }

    @Test fun projectMetadataAndSafeFileNameArePortable() {
        val settings = HistoryExportSettings("../Weather / Проект")
        val at = Instant.parse("2026-10-20T01:45:30Z")
        val metadata = settings.metadata(at)
        assertEquals(at.toString(), metadata.exportedAt)
        assertEquals(settings.projectName, metadata.projectName)
        val name = settings.fileName("2026-10-20_08-45-30")
        assertTrue(name.endsWith("_2026-10-20_08-45-30.actron.json"))
        assertFalse('/' in name || '\\' in name)
        assertEquals(metadata, codec.decode(codec.encode(recording().copy(metadata = metadata))).metadata)
    }

    @Test fun redactionRunsOnlyOnExportCopy() {
        val timeline = HistoryTimeline<String>()
        timeline.append(timeline.machine("m"), 0, HistoryDetails("secret", "Action", "Handled", emptyList(), emptyList()), "private")
        val recording = timeline.recording("test/1", { "redacted" }, { it.copy(input = "hidden") })
        assertEquals(
            "hidden",
            recording.entries
                .single()
                .details.input,
        )
        assertEquals(
            "private",
            timeline.entries.value
                .single()
                .snapshot,
        )
    }
}
