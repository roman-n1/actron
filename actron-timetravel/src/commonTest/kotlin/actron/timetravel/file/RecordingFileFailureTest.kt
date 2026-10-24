@file:OptIn(actron.core.ExperimentalActronApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.timetravel.file

import actron.core.Action
import actron.core.Event
import actron.core.InputAttribution
import actron.observability.MachineGroupId
import actron.observability.StoreInstanceId
import actron.observability.file.InMemorySegmentStorage
import actron.observability.file.SegmentOutput
import actron.observability.file.SegmentStorage
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineGroup
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineStore
import actron.statechart.machine.MachineTime
import actron.test.dispatchAndAwait
import actron.test.startAndAwait
import actron.timetravel.GroupRecorder
import actron.timetravel.RecordingCodec
import actron.timetravel.ReplaySession
import actron.timetravel.verify
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Exercise both the member recording writer and the group order writer at storage boundaries. */
class RecordingFileFailureTest {
    @Serializable private data object Tick : Action

    @Serializable private data object Ev : Event

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val machine =
        Machine<Int, Tick, Int, Ev>(
            DefinitionId("file-failures"),
            DefinitionVersion("1"),
            StateChartDefinition(root, listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root)), emptyList()),
        ) {
            onAction(root, ActionMatcher.of<Tick>("Tick")) { context += 1 }
        }
    private val codec = RecordingCodec(Int.serializer(), Tick.serializer(), Int.serializer(), Ev.serializer())
    private val memberId = StoreInstanceId("member")
    private val groupId = MachineGroupId("group")

    private enum class Kind { Member, Order }

    private class Storage(
        val targetsOrder: Boolean,
        val partialWrites: Boolean = false,
        val fail: (String, Int) -> Unit,
    ) : SegmentStorage {
        val memory = InMemorySegmentStorage()
        private val calls = mutableMapOf<String, Int>()
        var opened = 0
        var closed = 0

        private fun target(name: String) = GroupRecordingFileFormat.parseSegmentName(name) { _, _ -> } == targetsOrder

        private fun check(
            name: String,
            operation: String,
        ) {
            if (!target(name)) return
            val call = (calls[operation] ?: 0) + 1
            calls[operation] = call
            fail(operation, call)
        }

        override fun list() = memory.list()

        override fun read(name: String) = memory.read(name)

        override fun delete(name: String) {
            check(name, "delete")
            memory.delete(name)
        }

        override fun append(name: String): SegmentOutput {
            check(name, "append")
            val output = memory.append(name)
            if (target(name)) opened++
            return object : SegmentOutput {
                override fun write(bytes: ByteArray) {
                    try {
                        check(name, "write")
                    } catch (t: Throwable) {
                        if (partialWrites) output.write(bytes.copyOf(bytes.size / 2))
                        throw t
                    }
                    output.write(bytes)
                }

                override fun flush() {
                    check(name, "flush")
                    output.flush()
                }

                override fun close() {
                    if (target(name)) closed++
                    try {
                        check(name, "close")
                    } finally {
                        output.close()
                    }
                }
            }
        }
    }

    private sealed interface FormatWriter {
        val observer: actron.statechart.machine.DecisionObserver<Int, Tick, Int, Ev>

        suspend fun close()

        class Order(
            val files: GroupRecordingFileSink,
            override val observer: actron.statechart.machine.DecisionObserver<Int, Tick, Int, Ev>,
        ) : FormatWriter {
            override suspend fun close() = files.close()
        }

        class Member(
            val file: RecordingFileSink<Int, Tick, Int, Ev>,
        ) : FormatWriter {
            override val observer get() = file

            override suspend fun close() = file.close()
        }
    }

    private inner class Writer(
        scope: TestScope,
        val kind: Kind,
        val storage: Storage,
        config: RecordingFileConfig = RecordingFileConfig(),
        throwCallback: Boolean = false,
    ) {
        val job = SupervisorJob()
        val failures = mutableListOf<Throwable>()
        val uncaught = mutableListOf<Throwable>()
        val executionScope = CoroutineScope(StandardTestDispatcher(scope.testScheduler) + job + CoroutineExceptionHandler { _, e -> uncaught += e })
        val group = MachineGroup()
        val configured =
            config.copy(onFailure = {
                failures += it
                if (throwCallback) throw IllegalStateException("failure callback")
            })
        val format: FormatWriter =
            when (kind) {
                Kind.Order -> {
                    val files = GroupRecordingFileSink(group, groupId, storage, executionScope, configured)
                    FormatWriter.Order(files, files.member(memberId, machine, 0, codec))
                }

                Kind.Member -> {
                    FormatWriter.Member(RecordingFileSink(memberId, machine, 0, codec, storage, executionScope, configured))
                }
            }
        val observer get() = format.observer
        val files get() = (format as FormatWriter.Order).files
        var snapshot = machine.initialSnapshot(0)

        fun step() {
            val input = if (snapshot.revision == 0L) MachineInput.Start(MachineTime.Zero) else MachineInput.Dispatch(Tick, MachineTime.Zero)
            val decision = machine.decide(snapshot, input)
            observer.onCommitted(InputAttribution.Unattributed, input, decision)
            snapshot = decision.snapshot
        }

        suspend fun close() {
            format.close()
            job.cancelAndJoin()
        }
    }

    @Test
    fun failuresWhileOpeningWritingFlushingOrClosingReleaseEveryOutput() =
        runTest {
            for (kind in Kind.entries) {
                // Member: header, checkpoint, step, end. Order: header, entry, end.
                val cases =
                    listOf(
                        "append" to 1,
                        "write" to 1,
                        "write" to 2,
                        "write" to if (kind == Kind.Member) 3 else 2,
                        "write" to if (kind == Kind.Member) 4 else 3,
                        "close" to 1,
                        "flush" to 1,
                    )
                for ((operation, call) in cases.distinct()) {
                    val failure = IllegalStateException("$kind $operation $call")
                    val storage = Storage(kind == Kind.Order) { op, n -> if (op == operation && n == call) throw failure }
                    val writer = Writer(this, kind, storage, RecordingFileConfig(flushEveryFrames = 1))
                    writer.step()
                    runCurrent()
                    writer.close()
                    assertEquals(storage.opened, storage.closed, failure.message)
                    assertEquals(listOf<Throwable>(failure), writer.failures, failure.message)
                    assertTrue(writer.uncaught.isEmpty(), writer.uncaught.toString())
                }
            }
        }

    @Test
    fun storageFailureStaysPrimaryWhenClosingAlsoFails() =
        runTest {
            for (kind in Kind.entries) {
                val failure = IllegalStateException("write")
                val cleanupFailure = IllegalArgumentException("close")
                val storage =
                    Storage(kind == Kind.Order) { op, n ->
                        when {
                            op == "write" && n == 1 -> throw failure
                            op == "close" -> throw cleanupFailure
                            else -> Unit
                        }
                    }
                val writer = Writer(this, kind, storage)
                writer.step()
                runCurrent()
                writer.close()
                assertEquals(1, storage.closed, kind.name)
                assertSame(failure, writer.failures.single())
                assertEquals(listOf(cleanupFailure), failure.suppressedExceptions)
            }
        }

    @Test
    fun endFrameFailureStaysPrimaryWhenClosingAlsoFails() =
        runTest {
            for (kind in Kind.entries) {
                val failure = IllegalStateException("end frame")
                val cleanupFailure = IllegalArgumentException("close")
                val endWrite = if (kind == Kind.Member) 4 else 3
                val storage =
                    Storage(kind == Kind.Order) { operation, call ->
                        when {
                            operation == "write" && call == endWrite -> throw failure
                            operation == "close" -> throw cleanupFailure
                            else -> Unit
                        }
                    }
                val writer = Writer(this, kind, storage)
                writer.step()
                runCurrent()
                assertTrue(writer.failures.isEmpty())
                writer.close()
                assertSame(failure, writer.failures.single(), kind.name)
                assertEquals(listOf(cleanupFailure), failure.suppressedExceptions)
                assertEquals(1, storage.closed, kind.name)
                assertTrue(writer.uncaught.isEmpty())
            }
        }

    @Test
    fun cancellingTheWriterScopeReleasesTheHandleWithoutCompletingTheSegment() =
        runTest {
            for (kind in Kind.entries) {
                val storage = Storage(kind == Kind.Order) { _, _ -> }
                val writer = Writer(this, kind, storage)
                writer.step()
                runCurrent()
                assertEquals(1, storage.opened)
                writer.job.cancelAndJoin()
                writer.close()
                assertEquals(1, storage.closed, kind.name)
                assertTrue(writer.failures.isEmpty(), writer.failures.toString())
                if (kind == Kind.Member) {
                    val contents = RecordingFiles(storage).read(memberId, codec)
                    val name = storage.list().single().name
                    assertTrue(
                        !RecordingFileFormat.decodeSegment(name, storage.read(name), codec).finished,
                        "cancelled writer must not append an end frame",
                    )
                    kotlin.test.assertFalse(ReplaySession(machine, checkNotNull(contents.recording)).verify { error("Unexpected replay mismatch: $it") })
                } else {
                    val name = storage.list().single { GroupRecordingFileFormat.parseSegmentName(it.name) { _, _ -> } }.name
                    assertTrue(!GroupRecordingFileFormat.decodeSegment(name, storage.read(name)).finished)
                }
            }
        }

    @Test
    fun aThrowingFailureCallbackCannotLeakHandlesOrFailTheParentScope() =
        runTest {
            for (kind in Kind.entries) {
                val storage = Storage(kind == Kind.Order) { op, n -> if (op == "write" && n == 1) throw IllegalStateException("write") }
                val writer = Writer(this, kind, storage, throwCallback = true)
                writer.step()
                runCurrent()
                writer.close()
                assertEquals(1, storage.closed, kind.name)
                assertEquals(1, writer.failures.size)
                assertTrue(writer.uncaught.isEmpty(), writer.uncaught.toString())
            }
        }

    @Test
    fun rotationFailureClosesTheOldAndTheNewOutput() =
        runTest {
            for (kind in Kind.entries) {
                val failure = IllegalStateException("second header")
                val secondHeader = if (kind == Kind.Member) 5 else 4
                val storage = Storage(kind == Kind.Order) { op, n -> if (op == "write" && n == secondHeader) throw failure }
                val writer = Writer(this, kind, storage, RecordingFileConfig(maxSegmentBytes = 1))
                writer.step()
                runCurrent()
                writer.step()
                runCurrent()
                writer.close()
                assertEquals(2, storage.opened, kind.name)
                assertEquals(2, storage.closed, kind.name)
                assertEquals(listOf<Throwable>(failure), writer.failures)
            }
        }

    @Test
    fun partialWriteFailureLeavesOnlyTheValidPrefixReadable() =
        runTest {
            for (kind in Kind.entries) {
                val failure = IllegalStateException("partial frame")
                val secondFrame = if (kind == Kind.Member) 4 else 3
                val storage = Storage(kind == Kind.Order, partialWrites = true) { op, n -> if (op == "write" && n == secondFrame) throw failure }
                val writer = Writer(this, kind, storage)
                writer.step()
                runCurrent()
                writer.step()
                runCurrent()
                writer.close()
                assertEquals(1, storage.closed, kind.name)
                assertEquals(listOf<Throwable>(failure), writer.failures)
                if (kind == Kind.Member) {
                    val contents = RecordingFiles(storage).read(memberId, codec)
                    val recording = checkNotNull(contents.recording)
                    assertEquals(1, recording.length)
                    assertTrue(contents.marks.isNotEmpty())
                    kotlin.test.assertFalse(ReplaySession(machine, recording).verify { error("Unexpected replay mismatch: $it") })
                } else {
                    val name = storage.list().single { GroupRecordingFileFormat.parseSegmentName(it.name) { _, _ -> } }.name
                    val decoded = GroupRecordingFileFormat.decodeSegment(name, storage.read(name))
                    assertEquals(1, decoded.entries.size)
                    assertTrue(!decoded.finished && decoded.ending.withIssue {})
                }
            }
        }

    @Test
    fun aBrokenFileWriterDoesNotStopTheLiveMachineFromCommitting() =
        runTest {
            for (kind in Kind.entries) {
                val failure = IllegalStateException("header")
                val storage = Storage(kind == Kind.Order) { op, n -> if (op == "write" && n == 1) throw failure }
                val writer = Writer(this, kind, storage)
                val store =
                    MachineStore(
                        machine,
                        0,
                        CommandHandler<Int, Tick> { _, _ -> },
                        backgroundScope,
                        coroutineContext = StandardTestDispatcher(testScheduler),
                        observers = listOf(writer.observer),
                    )
                try {
                    store.startAndAwait()
                    repeat(3) { store.dispatchAndAwait(Tick) }
                    runCurrent()
                    assertEquals(3, store.currentState.context, kind.name)
                    assertEquals(listOf<Throwable>(failure), writer.failures)
                    assertEquals(1, storage.closed)
                    assertTrue(writer.uncaught.isEmpty(), writer.uncaught.toString())
                } finally {
                    store.close()
                    writer.close()
                }
            }
        }

    @Test
    fun duplicateFileMemberRegistrationKeepsTheOriginalSinkAndItsRecording() =
        runTest {
            val storage = Storage(true) { _, _ -> }
            val writer = Writer(this, Kind.Order, storage)
            writer.step()
            runCurrent()
            val original = checkNotNull(writer.files).members.getValue(memberId)
            val writersBefore = writer.job.children.count()
            assertFailsWith<IllegalArgumentException> { writer.files.member(memberId, machine, 0, codec) }
            assertSame(original, writer.files.members.getValue(memberId))
            assertEquals(writersBefore, writer.job.children.count(), "rejected registration must not launch a writer")
            writer.step()
            runCurrent()
            writer.close()
            val recording = checkNotNull(RecordingFiles(storage).read(memberId, codec).recording)
            assertEquals(2, recording.length)
            kotlin.test.assertFalse(ReplaySession(machine, recording).verify { error("Unexpected replay mismatch: $it") })
        }

    @Test
    fun aClosedGroupSinkRejectsNewMembersWithoutChangingItsRegistry() =
        runTest {
            val storage = Storage(true) { _, _ -> }
            val writer = Writer(this, Kind.Order, storage)
            writer.close()
            assertFailsWith<IllegalStateException> { checkNotNull(writer.files).member(StoreInstanceId("late"), machine, 0, codec) }
            assertEquals(
                setOf(memberId),
                kotlin.test
                    .assertNotNull(writer.files)
                    .members.keys,
            )
        }

    @Test
    fun duplicateInMemoryMemberRegistrationKeepsTheOriginalRecording() {
        val recorder = GroupRecorder()
        val observer = recorder.member(memberId, machine, 0)
        val input = MachineInput.Start(MachineTime.Zero)
        observer.onCommitted(InputAttribution.Unattributed, input, machine.decide(machine.initialSnapshot(0), input))
        assertFailsWith<IllegalArgumentException> { recorder.member(memberId, machine, 0) }
        assertEquals(
            1,
            recorder
                .recording()
                .members
                .getValue(memberId)
                .length,
        )
    }
}
