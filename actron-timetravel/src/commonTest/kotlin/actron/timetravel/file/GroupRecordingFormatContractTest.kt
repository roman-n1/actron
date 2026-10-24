package actron.timetravel.file

import actron.observability.MachineGroupId
import actron.observability.StoreInstanceId
import actron.observability.file.Framing
import actron.observability.file.SegmentMark
import actron.statechart.machine.EffectId
import actron.statechart.machine.MessageId
import actron.timetravel.GroupRoute
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GroupRecordingFormatContractTest {
    private val member = StoreInstanceId("member")
    private val header = GroupSegmentHeader(3, MachineGroupId("group"), listOf(member), listOf(GroupRoute(member, member)), emptySet(), 0, 0, emptyList())
    private val headerJson = """{"formatVersion":3,"group":"group","members":["member"],"routes":[{"from":"member","to":"member"}],"sourceIds":[],"index":0,"firstEntry":0}"""

    private fun frame(
        tag: Int,
        json: String,
    ): ByteArray = Framing.frame(byteArrayOf(tag.toByte()) + json.encodeToByteArray())

    @Test
    fun canonicalHeaderAndEntryKeepTheirBytesAndOmittedDefaults() {
        val expectedHeader = "KOMAGRPO".encodeToByteArray() + frame(1, headerJson)
        assertContentEquals(expectedHeader, GroupRecordingFileFormat.header(header))
        assertContentEquals(frame(2, """{"store":"member","step":4}"""), GroupRecordingFileFormat.entryFrame(GroupOrderEntry(member, 4)))
        val message = MessageId(member, EffectId(7))
        assertContentEquals(
            frame(2, """{"store":"member","step":5,"sent":[{"from":"member","effect":7}],"received":{"from":"member","effect":7}}"""),
            GroupRecordingFileFormat.entryFrame(GroupOrderEntry(member, 5, listOf(message), message)),
        )
    }

    @Test
    fun legacyHeadersWithoutCutsAndPairsDecodeWithoutReencoding() {
        for (version in 1..2) {
            val bytes = "KOMAGRPO".encodeToByteArray() + frame(1, headerJson.replace("\"formatVersion\":3", "\"formatVersion\":$version")) + Framing.END
            val decoded = assertIs<DecodedGroupSegment.Readable>(GroupRecordingFileFormat.decodeSegment("legacy", bytes))
            assertEquals(header.copy(fileFormatVersion = version), decoded.header)
            assertTrue(decoded.finished)
            assertTrue(decoded.entries.isEmpty())
        }
    }

    @Test
    fun invalidEntryKeepsOnlyValidPrefixAndItsFrameOffset() {
        val prefix = GroupRecordingFileFormat.header(header) + GroupRecordingFileFormat.entryFrame(GroupOrderEntry(member, 0))
        val invalid = frame(2, """{"store":"member","step":1,"received":{"from":"member"}}""")
        val bytes = prefix + invalid + GroupRecordingFileFormat.entryFrame(GroupOrderEntry(member, 2)) + Framing.END
        val decoded = assertIs<DecodedGroupSegment.Readable>(GroupRecordingFileFormat.decodeSegment("broken", bytes))
        assertEquals(listOf(GroupOrderEntry(member, 0)), decoded.entries)
        decoded.ending
            .withIssue { issue ->
                val corrupt = assertIs<SegmentMark.Corrupt>(issue)
                assertEquals(prefix.size, corrupt.offset)
                assertEquals(1, corrupt.recordsRead)
                assertTrue(corrupt.reason.startsWith("entry:"))
            }.also { assertTrue(it) }
        assertTrue(!decoded.finished)
    }

    @Test
    fun invalidHeaderIsUnreadableEvenWhenVersionIsFromTheFuture() {
        val json = headerJson.replace("\"formatVersion\":3", "\"formatVersion\":999").replace("\"to\":\"member\"", "\"to\":\"member\",\"pair\":42")
        val decoded = assertIs<DecodedGroupSegment.Unreadable>(GroupRecordingFileFormat.decodeSegment("future", "KOMAGRPO".encodeToByteArray() + frame(1, json) + Framing.END))
        val corrupt = assertIs<SegmentMark.Corrupt>(decoded.issue)
        assertEquals(8, corrupt.offset)
        assertEquals(0, corrupt.recordsRead)
        assertEquals("header: [Actron] Route pair must be a JSON string", corrupt.reason)
    }
}
