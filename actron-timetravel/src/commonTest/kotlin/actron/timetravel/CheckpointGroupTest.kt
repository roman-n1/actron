@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.statechart.StateId
import actron.statechart.machine.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.test.*

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
        write: () -> Unit = {},
    ) = checkpointGroup(::initial) {
        machine(machinePersistence(count(version)), { it.count }, { frame, cp ->
            write()
            frame.copy(count = cp)
        })
        machines(machinePersistence(text()), { it.texts }, { frame, cps ->
            write()
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
}
