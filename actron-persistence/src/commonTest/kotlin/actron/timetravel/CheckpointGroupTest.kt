package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.statechart.StateId
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.stateMachine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class CheckpointGroupTest {
    @Serializable private data object Act : Action

    @Serializable private data object Ev : Event

    private fun count(version: String = "1") = stateMachine<Int, Act, String, Ev>(DefinitionId("count"), DefinitionVersion(version), StateId("Idle")) { state(StateId("Idle")) {} }

    private fun text() = stateMachine<String, Act, Int, Ev>(DefinitionId("text"), DefinitionVersion("1"), StateId("Idle")) { state(StateId("Idle")) {} }

    private data class Frame(
        val count: ExecutorCheckpoint<Int, String>,
        val texts: List<ExecutorCheckpoint<String, Int>> = emptyList(),
    )

    private fun initial() = Frame(ExecutorCheckpoint.initial(count().initialSnapshot(0)))

    private fun group(
        version: String = "1",
        create: () -> Frame = ::initial,
        write: (String) -> Unit = {},
    ) = checkpointGroup(create) {
        machine(machinePersistence(count(version)), { it.count }, { frame, cp ->
            write("count")
            frame.copy(count = cp)
        })
        machines(machinePersistence(text()), { it.texts }, { frame, cps ->
            write("text")
            frame.copy(texts = cps)
        })
    }

    @Test
    fun heterogeneousMembersAndOptionalLifetimesRoundTripInOrder() {
        val group = group()
        for (texts in listOf(emptyList(), listOf("one"), listOf("two", "one"))) {
            val frame = Frame(ExecutorCheckpoint.initial(count().initialSnapshot(42)), texts.map { ExecutorCheckpoint.initial(text().initialSnapshot(it)) })
            val saved = group.save(frame)
            assertEquals(frame, group.restore(saved))
            assertEquals(saved, group.save(group.restore(saved)))
        }
    }

    @Test
    fun incompatibleMemberDoesNotInvokeAnyFrameWriter() {
        var writes = 0
        val saved = group().save(initial())
        assertFailsWith<IllegalArgumentException> { group("2") { writes++ }.restore(saved) }
        assertEquals(0, writes)
        val document = Json.parseToJsonElement(saved).jsonObject
        val members = document.getValue("machines").jsonObject.toMutableMap()
        members["text"] = JsonArray(listOf(JsonPrimitive("broken")))
        val broken = JsonObject(document + ("machines" to JsonObject(members))).toString()
        assertFailsWith<IllegalArgumentException> { group(write = { writes++ }).restore(broken) }
        assertEquals(0, writes, "A later invalid member must not partially apply earlier members")
    }

    @Test
    fun rejectsInvalidFormatMissingExtraAndWrongCardinality() {
        val group = group()
        val document = Json.parseToJsonElement(group.save(initial())).jsonObject
        val members = document.getValue("machines").jsonObject
        val bad =
            listOf(
                "broken",
                JsonObject(document + ("format" to JsonPrimitive(99))).toString(),
                JsonObject(document + ("machines" to JsonObject(members - "count"))).toString(),
                JsonObject(document + ("machines" to JsonObject(members + ("extra" to JsonArray(emptyList()))))).toString(),
                JsonObject(document + ("machines" to JsonObject(members + ("count" to JsonArray(emptyList()))))).toString(),
            )
        bad.forEach { assertFailsWith<IllegalArgumentException> { group.restore(it) } }
    }

    @Test
    fun rejectsDuplicateDefinitionsAndEmptySchema() {
        assertFailsWith<IllegalArgumentException> { checkpointGroup(::initial) {} }
        assertFailsWith<IllegalArgumentException> {
            checkpointGroup(::initial) {
                repeat(2) { machine(machinePersistence(count()), { it.count }, { frame, cp -> frame.copy(count = cp) }) }
            }
        }
    }

    @Test
    fun everyMemberIsValidatedBeforeInitialAndWritersRun() {
        val calls = mutableListOf<String>()
        val group =
            group(create = {
                calls += "initial"
                initial()
            }, write = { calls += it })
        val saved = group.save(initial())
        val document = Json.parseToJsonElement(saved).jsonObject
        val members = document.getValue("machines").jsonObject
        val broken = JsonObject(document + ("machines" to JsonObject(members + ("text" to JsonArray(listOf(JsonPrimitive("broken"))))))).toString()

        assertFailsWith<IllegalArgumentException> { group.restore(broken) }
        assertEquals(emptyList(), calls)
        assertEquals(initial(), group.restore(saved))
        assertEquals(listOf("initial", "count", "text"), calls)
    }

    @Test
    fun singularCardinalityIsCheckedBeforePayloadsOrFrameCreation() {
        val calls = mutableListOf<String>()
        val group =
            group(create = {
                calls += "initial"
                initial()
            }, write = { calls += it })
        val document = Json.parseToJsonElement(group.save(initial())).jsonObject
        val members = document.getValue("machines").jsonObject
        for (values in listOf(emptyList(), listOf("broken", "broken"))) {
            val invalid = JsonObject(document + ("machines" to JsonObject(members + ("count" to JsonArray(values.map(::JsonPrimitive)))))).toString()

            val error = assertFailsWith<IllegalArgumentException> { group.restore(invalid) }

            assertEquals("Expected one checkpoint for count", error.message)
            assertEquals(emptyList(), calls)
        }
    }

    @Test
    fun registeringTheSameDefinitionAsOneAndManyIsRejected() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                checkpointGroup(::initial) {
                    machine(machinePersistence(count()), { it.count }, { frame, cp -> frame.copy(count = cp) })
                    machines(machinePersistence(count()), { listOf(it.count) }, { frame, cps -> frame.copy(count = cps.single()) })
                }
            }
        assertEquals("Duplicate checkpoint group definition: count", error.message)
    }

    @Test
    fun laterBuilderRegistrationsDoNotChangeTheBuiltGroup() {
        val builders = mutableListOf<CheckpointGroupBuilder<Frame>>()
        val group =
            checkpointGroup(::initial) {
                builders += this
                machine(machinePersistence(count()), { it.count }, { frame, cp -> frame.copy(count = cp) })
            }
        val saved = group.save(initial())

        builders.single().machines(machinePersistence(text()), { it.texts }, { frame, cps -> frame.copy(texts = cps) })

        assertEquals(saved, group.save(initial()))
        assertEquals(initial(), group.restore(saved))
    }

    @Test
    fun writerFailurePropagatesAndStopsLaterWriters() {
        val calls = mutableListOf<String>()
        val expected = IllegalStateException("host writer failed")
        val group =
            group(create = {
                calls += "initial"
                initial()
            }, write = { id ->
                calls += id
                throw expected
            })
        val saved = group.save(initial())

        val error = assertFailsWith<IllegalStateException> { group.restore(saved) }

        assertSame(expected, error)
        assertEquals(listOf("initial", "count"), calls)
    }
}
