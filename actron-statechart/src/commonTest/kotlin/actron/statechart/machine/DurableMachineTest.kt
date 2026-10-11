package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.statechart.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class DurableMachineTest {
    private data object Leave : Action
    private data object Applied : Action
    private data object Retry : Action
    private data class Pay(val amount: Int)
    private val active = StateId("active")
    private val left = StateId("left")
    private val machine = Machine<Int, Action, Pay, Event>(DefinitionId("pay"), DefinitionVersion("1"),
        StateChartDefinition(active, listOf(AtomicState(active), AtomicState(left)), listOf(
            Transition(active, left, ActionMatcher.of<Leave>("leave")),
            Transition(active, left, Trigger.After(10.seconds)),
        ))) {
        onEnter(active) { command(Pay(10)) }
        onAction(active, ActionMatcher.of<Applied>("applied")) { context++ }
        onAction(left, ActionMatcher.of<Applied>("applied")) { context++ }
    }

    private class Storage<C : Any, CMD : Any> : DurableMachineStorage<C, CMD> {
        val commits = mutableListOf<DurableCheckpoint<C, CMD>>()
        var saved: DurableCheckpoint<C, CMD>
            get() = commits.last()
            set(value) { commits += value }
        var fail = false
        var conflict = false
        override suspend fun read(accept: (DurableCheckpoint<C, CMD>) -> Unit): Boolean { if (commits.isEmpty()) return false; accept(saved); return true }
        override suspend fun commit(expectedGeneration: DurableGeneration, checkpoint: DurableCheckpoint<C, CMD>): Boolean {
            if (fail) { fail = false; error("disk failed") }
            if (conflict) return false
            if ((if (commits.isEmpty()) DurableGeneration.Fresh else DurableGeneration.Existing(saved.generation)) != expectedGeneration) return false
            saved = checkpoint
            return true
        }
    }
    private fun runner(storage: Storage<Int, Pay>) = DurableMachine(machine, storage) { _, classify -> classify(IdempotencyKey("order-1")) }

    @Test fun crashAfterExternalEffectRetriesTheSameKey_andReceiptPreventsDoublePayment() = runTest {
        val storage = Storage<Int, Pay>()
        runner(storage).initialize(0, MachineTime.Zero)
        val receipts = mutableSetOf<IdempotencyKey>()
        var balance = 0
        val attempts = mutableListOf<Long>()
        val handler = DurableCommandHandler<Pay, Action> { key, command, attempt, emit ->
            attempts += attempt
            if (receipts.add(key)) balance += command.amount
            storage.fail = attempt == 1L // external side effect succeeded, atomic ack fails
            emit(Applied)
        }
        assertFailsWith<IllegalStateException> { runner(storage).executeObserved(handler) { MachineTime.Zero } }
        assertEquals(DurableStatus.Pending, kotlin.test.assertNotNull(storage.saved).outbox.values.single().status)
        assertEquals(10, balance)
        val restarted = runner(storage)
        val result = restarted.executeObserved(handler) { MachineTime.Zero }
        assertEquals(listOf(1L, 2L), attempts)
        assertEquals(10, balance)
        assertEquals(1, kotlin.test.assertNotNull(result).checkpoint.snapshot.context)
        assertEquals(DurableStatus.Completed, result.checkpoint.outbox.values.single().status)
        assertFalse(restarted.executeNext(handler, { error("Unexpected durable commit: $it") }) { MachineTime.Zero })
    }

    @Test fun stateExitWhileIoAwaitsDoesNotLoseTheDurableIntent() = runTest {
        val storage = Storage<Int, Pay>()
        val runner = runner(storage)
        runner.initialize(0, MachineTime.Zero)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val drain = async {
            runner.executeObserved(DurableCommandHandler { _, _, _, emit -> started.complete(Unit); release.await(); emit(Applied) }) { MachineTime.Zero }
        }
        started.await()
        runner.commit(MachineInput.Dispatch(Leave, MachineTime.Zero))
        assertTrue(kotlin.test.assertNotNull(storage.saved).snapshot.commands.isEmpty())
        assertEquals(DurableStatus.Pending, kotlin.test.assertNotNull(storage.saved).outbox.values.single().status)
        release.complete(Unit)
        val result = kotlin.test.assertNotNull(drain.await())
        assertTrue(result.checkpoint.snapshot.isActive(left))
        assertEquals(1, result.checkpoint.snapshot.context)
    }

    @Test fun failedInitialPersistenceReturnsNoIntents_andRestoreKeepsTimers() = runTest {
        val storage = Storage<Int, Pay>()
        storage.fail = true
        assertFailsWith<IllegalStateException> { runner(storage).initialize(0, MachineTime.Zero) }
        assertTrue(storage.commits.isEmpty())
        val first = runner(storage).initialize(0, MachineTime.Zero)
        val restored = kotlin.test.assertNotNull(runner(storage).restoreObserved())
        assertEquals(first.checkpoint, restored)
        assertTrue(runner(storage).dueTimers(MachineTime.Zero + 9.seconds).isEmpty())
        assertEquals(1, runner(storage).dueTimers(MachineTime.Zero + 11.seconds).size)
        assertFailsWith<IllegalArgumentException> { runner(storage).commit(MachineInput.Dispatch(Leave, MachineTime.Zero + (-1).seconds)) }
    }

    @Test fun duplicatePendingAndCompletedKeysNeverLeaveUnownedCommandRegistrations() = runTest {
        val retryMachine = Machine<Int, Action, Pay, Event>(machine.id, machine.version,
            StateChartDefinition(active, listOf(AtomicState(active)), emptyList())) {
            onAction(active, ActionMatcher.of<Retry>("retry")) { command(Pay(10)) }
        }
        val storage = Storage<Int, Pay>()
        val durable = DurableMachine(retryMachine, storage) { _, classify -> classify(IdempotencyKey("same")) }
        durable.initialize(0, MachineTime.Zero)
        repeat(2) { durable.commit(MachineInput.Dispatch(Retry, MachineTime.Zero)) }
        assertEquals(2, kotlin.test.assertNotNull(storage.saved).snapshot.commands.size)
        assertEquals(1, kotlin.test.assertNotNull(storage.saved).outbox.size)
        var calls = 0
        durable.executeObserved(DurableCommandHandler { _, _, _, emit -> calls++ }) { MachineTime.Zero }
        assertTrue(kotlin.test.assertNotNull(storage.saved).snapshot.commands.isEmpty())
        durable.commit(MachineInput.Dispatch(Retry, MachineTime.Zero))
        assertTrue(kotlin.test.assertNotNull(storage.saved).snapshot.commands.isEmpty())
        assertFalse(durable.executeNext(DurableCommandHandler { _, _, _, emit -> calls++ }, { error("Unexpected durable commit: $it") }) { MachineTime.Zero })
        assertEquals(1, calls)
    }

    @Test fun ordinaryWorkCanBeRestartedFromStoredPayloads() = runTest {
        val storage = Storage<Int, Pay>()
        val durable = DurableMachine(machine, storage) { _, _ -> }
        val first = durable.initialize(0, MachineTime.Zero)
        assertEquals(1, first.ephemeralCommands.size)
        val restored = DurableMachine(machine, storage) { _, _ -> }.initialize(100, MachineTime.Zero)
        assertEquals(first.ephemeralCommands, restored.ephemeralCommands)
        assertEquals(0, restored.checkpoint.snapshot.context)
    }

    @Test fun completedReceiptCleanupPersistsWorkCreatedByAutomaticTransitions() = runTest {
        val chart = StateChartDefinition(active, listOf(AtomicState(active), AtomicState(left)), listOf(
            Transition(active, left, Trigger.Eventless, guard = actron.statechart.GuardKey("retry-completed")),
        ))
        val retryMachine = Machine<Int, Action, Pay, Event>(machine.id, machine.version, chart) {
            onEnter(active) { command(Pay(10)) }
            onAction(active, ActionMatcher.of<Retry>("retry")) { context++; command(Pay(10)) }
            guard("retry-completed") { snapshot, _ -> snapshot.context == 1 && snapshot.commands.isEmpty() }
            onEnter(left) { command(Pay(20)) }
        }
        val storage = Storage<Int, Pay>()
        val durable = DurableMachine(retryMachine, storage) { command, classify -> if (command.amount == 10) classify(IdempotencyKey("same")) }
        durable.initialize(0, MachineTime.Zero)
        durable.executeObserved(DurableCommandHandler { _, _, _, emit -> }) { MachineTime.Zero }
        val committed = durable.commit(MachineInput.Dispatch(Retry, MachineTime.Zero))
        assertTrue(committed.checkpoint.snapshot.isActive(left))
        assertEquals(Pay(20), committed.ephemeralCommands.single().command)
        assertEquals(committed.ephemeralCommands, durable.initialize(0, MachineTime.Zero).ephemeralCommands)
        assertFalse(durable.executeNext(DurableCommandHandler { _, _, _, emit -> error("Receipt must suppress IO") }, { error("Unexpected durable commit: $it") }) { MachineTime.Zero })
    }

    @Test fun receiptCleanupFailureRollsBackWithTheOriginalCause() = runTest {
        val failure = IllegalStateException("automatic cleanup failed")
        val retryMachine = Machine<Int, Action, Pay, Event>(machine.id, machine.version,
            StateChartDefinition(active, listOf(AtomicState(active), AtomicState(left)), listOf(
                Transition(active, left, Trigger.Eventless, guard = actron.statechart.GuardKey("cleanup"), effect = actron.statechart.EffectKey("fail")),
            ))) {
            onEnter(active) { command(Pay(10)) }
            onAction(active, ActionMatcher.of<Retry>("retry")) { context++; command(Pay(10)) }
            guard("cleanup") { snapshot, _ -> snapshot.context == 1 && snapshot.commands.isEmpty() }
            effect("fail") { _, _ -> throw failure }
        }
        val storage = Storage<Int, Pay>()
        val durable = DurableMachine(retryMachine, storage) { _, classify -> classify(IdempotencyKey("same")) }
        durable.initialize(0, MachineTime.Zero)
        durable.executeObserved(DurableCommandHandler { _, _, _, emit -> }) { MachineTime.Zero }
        val before = storage.saved
        assertSame(failure, assertFailsWith<IllegalStateException> { durable.commit(MachineInput.Dispatch(Retry, MachineTime.Zero)) })
        assertSame(before, storage.saved)
    }

    @Test fun restoreRejectsMalformedPayloadOwnershipAndOutboxAliases() = runTest {
        val storage = Storage<Int, Pay>()
        val durable = runner(storage)
        durable.initialize(0, MachineTime.Zero)
        val base = kotlin.test.assertNotNull(storage.saved)
        val command = base.outbox.values.single()
        val ordinary = CommandRegistration(command.registration, command.command,
            base.snapshot.commands.getValue(command.registration).scope)
        val otherKey = IdempotencyKey("other")
        val cases = listOf(
            "unissued alias" to base.copy(outbox = mapOf(command.key to command.copy(registrations = command.registrations + CommandId(999)))),
            "missing original registration" to base.copy(outbox = mapOf(command.key to command.copy(registrations = emptySet()))),
            "two outbox owners" to base.copy(outbox = base.outbox + (otherKey to command.copy(key = otherKey))),
            "ordinary and durable owners" to base.copy(ephemeral = mapOf(ordinary.id to ordinary)),
            "wrong ordinary lane" to base.copy(outbox = emptyMap(), ephemeral = mapOf(ordinary.id to ordinary.copy(lane = LaneId("wrong"), policy = ConcurrencyPolicy.Latest))),
            "completed receipt owns live work" to base.copy(outbox = mapOf(command.key to command.copy(status = DurableStatus.Completed))),
            "unstarted checkpoint" to base.copy(snapshot = machine.initialSnapshot(0), outbox = emptyMap()),
        )
        for ((label, invalid) in cases) {
            storage.saved = invalid
            assertFailsWith<IllegalArgumentException>(label) { durable.restoreObserved() }
        }
    }

    @Test fun cachedCommandFeedbackIsBoundedAndRollsBackTheEntireCommit() = runTest {
        val retryMachine = Machine<Int, Action, Pay, Event>(machine.id, machine.version,
            StateChartDefinition(active, listOf(AtomicState(active)), listOf(
                Transition(active, active, Trigger.Eventless, guard = actron.statechart.GuardKey("cleanup")),
            ))) {
            onEnter(active) { command(Pay(10)) }
            onAction(active, ActionMatcher.of<Retry>("retry")) { context++; command(Pay(10)) }
            guard("cleanup") { snapshot, _ -> snapshot.context > 0 && snapshot.commands.isEmpty() }
        }
        val storage = Storage<Int, Pay>()
        val durable = DurableMachine(retryMachine, storage, maxReceiptCompletions = 3) { _, classify -> classify(IdempotencyKey("same")) }
        durable.initialize(0, MachineTime.Zero)
        durable.executeObserved(DurableCommandHandler { _, _, _, emit -> }) { MachineTime.Zero }
        val before = storage.saved
        val failure = assertFailsWith<IllegalStateException> { durable.commit(MachineInput.Dispatch(Retry, MachineTime.Zero)) }
        assertTrue(failure.message.orEmpty().contains("3 receipt completions"))
        assertSame(before, storage.saved)
    }

    @Test fun reservationConflictPreventsExternalIo() = runTest {
        val storage = Storage<Int, Pay>()
        val durable = runner(storage)
        durable.initialize(0, MachineTime.Zero)
        val before = storage.saved
        var calls = 0
        val handler = DurableCommandHandler<Pay, Action> { _, _, _, emit -> calls++ }
        storage.conflict = true
        assertFailsWith<DurableConflictException> { durable.executeObserved(handler) { MachineTime.Zero } }
        assertSame(before, storage.saved)
        assertEquals(0, calls)
        storage.conflict = false
        durable.executeObserved(handler) { MachineTime.Zero }
        assertEquals(1, calls)
    }

    @Test fun failedResultDecisionKeepsTheOutboxPendingAndCanBeRetried() = runTest {
        val failure = IllegalStateException("result failed")
        val failingMachine = Machine<Int, Action, Pay, Event>(machine.id, machine.version, machine.chart) {
            onEnter(active) { command(Pay(10)) }
            onAction(active, ActionMatcher.of<Applied>("applied")) { context++; command(Pay(20)); throw failure }
        }
        val storage = Storage<Int, Pay>()
        val durable = DurableMachine(failingMachine, storage) { _, classify -> classify(IdempotencyKey("same")) }
        val initial = durable.initialize(0, MachineTime.Zero).checkpoint.snapshot
        assertSame(failure, assertFailsWith<IllegalStateException> {
            durable.executeObserved(DurableCommandHandler { _, _, _, emit -> emit(Applied) }) { MachineTime.Zero }
        })
        assertEquals(initial, kotlin.test.assertNotNull(storage.saved).snapshot)
        assertEquals(DurableStatus.Pending, kotlin.test.assertNotNull(storage.saved).outbox.values.single().status)
        assertEquals(1L, kotlin.test.assertNotNull(storage.saved).outbox.values.single().attempts)
        val completed = kotlin.test.assertNotNull(durable.executeObserved(DurableCommandHandler { _, _, _, emit -> emit(Leave) }) { MachineTime.Zero })
        assertTrue(completed.checkpoint.snapshot.isActive(left))
        assertEquals(DurableStatus.Completed, completed.checkpoint.outbox.values.single().status)
        assertEquals(2L, completed.checkpoint.outbox.values.single().attempts)
        assertTrue(completed.ephemeralCommands.isEmpty())
    }

    @Test fun classifierFailureOrDuplicateKeyCannotPublishAnInitialCheckpoint() = runTest {
        val failure = IllegalStateException("classification failed")
        val failed = Storage<Int, Pay>()
        val first = DurableMachine(machine, failed) { _, classify ->
            classify(IdempotencyKey("one"))
            throw failure
        }
        assertSame(failure, assertFailsWith<IllegalStateException> { first.initialize(0, MachineTime.Zero) })
        assertTrue(failed.commits.isEmpty())
        val duplicated = Storage<Int, Pay>()
        val second = DurableMachine(machine, duplicated) { _, classify ->
            classify(IdempotencyKey("one"))
            classify(IdempotencyKey("two"))
        }
        assertFailsWith<IllegalStateException> { second.initialize(0, MachineTime.Zero) }
        assertTrue(duplicated.commits.isEmpty())
    }

    @Test fun aFailedOrDuplicateResultLeavesItsReservedOperationPending() = runTest {
        val failure = IllegalStateException("IO failed after producing a result")
        for (duplicate in listOf(false, true)) {
            val storage = Storage<Int, Pay>()
            val durable = runner(storage)
            val initial = durable.initialize(0, MachineTime.Zero).checkpoint.snapshot
            val thrown = assertFailsWith<IllegalStateException> {
                durable.executeNext(DurableCommandHandler { _, _, _, emit ->
                    emit(Applied)
                    if (duplicate) emit(Leave) else throw failure
                }) { MachineTime.Zero }
            }
            if (!duplicate) assertSame(failure, thrown)
            assertEquals(initial, kotlin.test.assertNotNull(storage.saved).snapshot)
            assertEquals(DurableStatus.Pending, kotlin.test.assertNotNull(storage.saved).outbox.values.single().status)
            assertEquals(1L, kotlin.test.assertNotNull(storage.saved).outbox.values.single().attempts)
        }
    }

    @Test fun aResultCannotBeEmittedAfterTheHandlerHasReturned() = runTest {
        val storage = Storage<Int, Pay>()
        val durable = runner(storage)
        durable.initialize(0, MachineTime.Zero)
        var late: (Action) -> Unit = { error("handler not called") }
        assertTrue(durable.executeNext(DurableCommandHandler { _, _, _, emit ->
            late = emit
            emit(Applied)
        }) { MachineTime.Zero })
        val committed = storage.saved
        assertFailsWith<IllegalStateException> { late(Leave) }
        assertSame(committed, storage.saved)
    }

    @Test fun semanticMigrationValidatesConfigurationWorkAndInvariants() {
        val old = machine.decide(machine.initialSnapshot(2), MachineInput.Start(MachineTime.Zero)).snapshot
        val newMachine = Machine<String, Action, Pay, Event>(machine.id, DefinitionVersion("2"), machine.chart) {
            invariant("not-empty") { it.context.isNotEmpty() }
        }
        val migration = SnapshotMigration<Int, String>(machine.version, newMachine.version) { snapshot ->
            MachineSnapshot(snapshot.definition, newMachine.version, snapshot.revision, snapshot.configuration, snapshot.context.toString(),
                snapshot.activations, snapshot.commands, snapshot.timers, snapshot.counters)
        }
        val result = newMachine.migrateSnapshot(old, migration)
        assertEquals("2", result.context)
        assertEquals(old.timers, result.timers)
        assertFailsWith<IllegalArgumentException> {
            newMachine.migrateSnapshot(old, SnapshotMigration(machine.version, newMachine.version) { migration.transform(it).copy(activations = emptyMap()) })
        }
        assertFailsWith<IllegalArgumentException> {
            SnapshotMigrations(listOf(SnapshotMigration<Int, Int>(DefinitionVersion("1"), DefinitionVersion("2")) { it.copy(version = DefinitionVersion("2")) },
                SnapshotMigration(DefinitionVersion("2"), DefinitionVersion("1")) { it.copy(version = DefinitionVersion("1")) }))
        }
    }
}

private suspend fun <C : Any, A : actron.core.Action, CMD : Any, E : actron.core.Event> DurableMachine<C, A, CMD, E>.executeObserved(
    handler: DurableCommandHandler<CMD, A>, now: () -> MachineTime,
): DurableCommit<C, CMD, E> {
    val observedObservations = mutableListOf<DurableCommit<C, CMD, E>>()
    val executed = executeNext(handler, { check(observedObservations.isEmpty()); observedObservations.add(it) }, now)
    kotlin.test.assertEquals(observedObservations.isNotEmpty(), executed)
    return observedObservations.single()
}

private suspend fun <C : Any, A : actron.core.Action, CMD : Any, E : actron.core.Event> DurableMachine<C, A, CMD, E>.restoreObserved(): DurableCheckpoint<C, CMD> {
    val observedObservations = mutableListOf<DurableCheckpoint<C, CMD>>()
    val found = restore { check(observedObservations.isEmpty()); observedObservations.add(it) }
    kotlin.test.assertEquals(observedObservations.isNotEmpty(), found)
    return observedObservations.single()
}
