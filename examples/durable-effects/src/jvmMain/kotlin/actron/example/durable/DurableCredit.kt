package actron.example.durable

import actron.core.Action
import actron.core.Event
import actron.core.ExceptionHandler
import actron.core.StateSaver
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.Delivery
import actron.statechart.machine.EffectPolicy
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.MachineStore
import actron.statechart.machine.MailboxConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

/** A business key created by the caller; it must survive every Store/process recreation. */
@Serializable
data class CreditIntent(
    val id: String,
    val amount: Long,
) {
    init {
        require(id.isNotBlank() && amount > 0)
    }
}

/** Business lifecycle, including the authentic completion preceding the next pending intent. */
@Serializable(with = OutboxSerializer::class)
sealed interface Outbox {
    val hasPending: Boolean get() = false

    fun pendingMatches(intent: CreditIntent): Boolean = false

    fun completedMatches(intent: CreditIntent): Boolean = false

    fun completedIdMatches(id: String): Boolean = false

    fun withPending(accept: (CreditIntent) -> Unit): Boolean = false

    fun withCompleted(accept: (CreditIntent) -> Unit): Boolean = false

    fun request(intent: CreditIntent): Outbox

    fun applied(intent: CreditIntent): Outbox = this

    sealed interface Ready : Outbox {
        override fun request(intent: CreditIntent): Outbox {
            if (completedIdMatches(intent.id)) {
                require(completedMatches(intent)) { "Another intent is pending" }
                return this
            }
            return Awaiting(intent, this)
        }
    }

    data object Unstarted : Ready

    data class Completed(
        val intent: CreditIntent,
    ) : Ready {
        override fun completedMatches(intent: CreditIntent): Boolean = this.intent == intent

        override fun completedIdMatches(id: String): Boolean = intent.id == id

        override fun withCompleted(accept: (CreditIntent) -> Unit): Boolean {
            accept(intent)
            return true
        }
    }

    data class Awaiting(
        val intent: CreditIntent,
        val previous: Ready = Unstarted,
    ) : Outbox {
        init {
            require(!previous.completedIdMatches(intent.id))
        }

        override val hasPending: Boolean get() = true

        override fun pendingMatches(intent: CreditIntent): Boolean = this.intent == intent

        override fun completedMatches(intent: CreditIntent): Boolean = previous.completedMatches(intent)

        override fun completedIdMatches(id: String): Boolean = previous.completedIdMatches(id)

        override fun withPending(accept: (CreditIntent) -> Unit): Boolean {
            accept(intent)
            return true
        }

        override fun withCompleted(accept: (CreditIntent) -> Unit): Boolean = previous.withCompleted(accept)

        override fun request(intent: CreditIntent): Outbox {
            require(pendingMatches(intent) || completedMatches(intent)) { "Another intent is pending" }
            return this
        }

        override fun applied(intent: CreditIntent): Outbox = if (pendingMatches(intent)) Completed(intent) else this
    }
}

/** Preserve the original schema-1 JSON fields, including real protocol JsonNull nodes. */
@Serializable
private data class OutboxWire(
    val pending: JsonElement = JsonNull,
    val completed: JsonElement = JsonNull,
)

private object OutboxSerializer : KSerializer<Outbox> {
    override val descriptor = OutboxWire.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: Outbox,
    ) {
        require(encoder is JsonEncoder) { "The disk outbox uses JSON" }
        encoder.encodeJsonElement(
            buildJsonObject {
                put("pending", JsonNull)
                put("completed", JsonNull)
                value.withPending { put("pending", encoder.json.encodeToJsonElement(it)) }
                value.withCompleted { put("completed", encoder.json.encodeToJsonElement(it)) }
            },
        )
    }

    override fun deserialize(decoder: Decoder): Outbox {
        require(decoder is JsonDecoder) { "The disk outbox uses JSON" }
        val wire = decoder.decodeSerializableValue(OutboxWire.serializer())
        val previous: Outbox.Ready =
            if (wire.completed == JsonNull) {
                Outbox.Unstarted
            } else {
                Outbox.Completed(decoder.json.decodeFromJsonElement<CreditIntent>(wire.completed))
            }
        return if (wire.pending == JsonNull) {
            previous
        } else {
            Outbox.Awaiting(decoder.json.decodeFromJsonElement<CreditIntent>(wire.pending), previous)
        }
    }
}

sealed interface CreditAction : Action {
    data class Request(
        val intent: CreditIntent,
    ) : CreditAction

    data class Applied(
        val intent: CreditIntent,
    ) : CreditAction
}

data class ApplyCredit(
    val intent: CreditIntent,
) : Event

val creditMachine: Machine<Outbox, CreditAction, Nothing, ApplyCredit> =
    run {
        val ready = StateId("Ready")
        Machine(
            DefinitionId("durable-credit"),
            DefinitionVersion("1"),
            StateChartDefinition(ready, listOf(AtomicState(ready)), emptyList()),
        ) {
            onEnter(ready) { context.withPending { event(ApplyCredit(it)) } }
            onAction(ready, ActionMatcher.of<CreditAction.Request>("Request")) {
                val intent = (action as CreditAction.Request).intent
                val next = context.request(intent)
                if (next != context) {
                    context = next
                    event(ApplyCredit(intent))
                }
            }
            onAction(ready, ActionMatcher.of<CreditAction.Applied>("Applied")) {
                val intent = (action as CreditAction.Applied).intent
                if (context.pendingMatches(intent)) context = context.applied(intent)
            }
        }
    }

/** Single writer only. Both the actual balance and the idempotency receipt share one commit. */
@Serializable
data class Ledger(
    val balance: Long = 0,
    val receipts: Map<String, Long> = emptyMap(),
) {
    init {
        require(receipts.all { (id, amount) -> id.isNotBlank() && amount > 0 })
        require(balance == receipts.values.fold(0L) { total, amount -> Math.addExact(total, amount) })
    }
}

@Serializable
private data class DomainFile(
    val schema: Int = 1,
    val outbox: Outbox,
)

@Serializable
private data class LedgerFile(
    val schema: Int = 1,
    val ledger: Ledger,
)

private val storageJson = Json { encodeDefaults = true }

/** POSIX local filesystem recipe; fails closed if atomic move or directory fsync is unsupported. */
internal fun durableReplace(
    path: Path,
    text: String,
) {
    val temporary = checkNotNull(Files.createTempFile(path.parent, "pending-", ".tmp"))
    try {
        checkNotNull(FileChannel.open(temporary, WRITE)).use { channel ->
            val bytes = checkNotNull(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
        checkNotNull(FileChannel.open(path.parent, READ)).use { it.force(true) }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

/** Persist only business data; live executor/mailbox identity is intentionally not restored. */
class DiskOutbox(
    private val path: Path,
) : StateSaver<MachineSnapshot<Outbox>> {
    val durable = MutableStateFlow(read())
    val savedRevision = MutableStateFlow(0L)
    private val barriers = mutableMapOf<CompletableDeferred<Unit>, (Outbox) -> Boolean>()

    /** Register before dispatch: later saves must not hide a successful durable completion. */
    internal suspend fun awaitSaved(
        predicate: (Outbox) -> Boolean,
        dispatch: () -> Unit,
    ) {
        val barrier = CompletableDeferred<Unit>()
        synchronized(barriers) { barriers[barrier] = predicate }
        try {
            dispatch()
            barrier.await()
        } finally {
            synchronized(barriers) {
                barriers.remove(barrier)
                Unit
            }
        }
    }

    private fun read(): Outbox =
        if (!Files.exists(path)) {
            Outbox.Unstarted
        } else {
            val decoded = storageJson.decodeFromString<DomainFile>(Files.readString(path))
            require(decoded.schema == 1) { "Unsupported domain schema" }
            decoded.outbox
        }

    override fun restore(initialState: MachineSnapshot<Outbox>): MachineSnapshot<Outbox> = creditMachine.initialSnapshot(durable.value)

    override fun save(state: MachineSnapshot<Outbox>) {
        durableReplace(path, storageJson.encodeToString(DomainFile(outbox = state.context)))
        durable.value = state.context // Publish only after persistence actually succeeded.
        savedRevision.value = state.revision
        val completed =
            synchronized(barriers) {
                barriers.filterValues { it(state.context) }.keys.toList()
            }
        // An immediate continuation may call back into the session. Resume it outside the
        // barrier monitor rather than carrying an application callback across that lock.
        completed.forEach { it.complete(Unit) }
    }
}

class DiskLedger(
    private val path: Path,
) {
    // Test seam inside the synchronous ledger operation, immediately before its real commit.
    internal var beforeCommit: () -> Unit = {}

    fun read(): Ledger =
        if (!Files.exists(path)) {
            Ledger()
        } else {
            val decoded = storageJson.decodeFromString<LedgerFile>(Files.readString(path))
            require(decoded.schema == 1) { "Unsupported ledger schema" }
            decoded.ledger
        }

    /** Returns false on redelivery, rejects reuse of an id for a different payload. */
    @Synchronized
    fun apply(intent: CreditIntent): Boolean {
        val previous = read()
        if (intent.id in previous.receipts) {
            require(previous.receipts.getValue(intent.id) == intent.amount) { "Intent id reused with another amount" }
            return false
        }
        val next = Ledger(Math.addExact(previous.balance, intent.amount), previous.receipts + (intent.id to intent.amount))
        beforeCommit()
        durableReplace(path, storageJson.encodeToString(LedgerFile(ledger = next)))
        return true
    }
}

enum class Boundary { BeforeEffect, AfterEffect, AfterDomainAck, AfterMailboxAck }

private class SessionFiles(
    val channel: FileChannel,
    val lock: FileLock,
    val saver: DiskOutbox,
    val ledger: DiskLedger,
) {
    fun close() {
        try {
            lock.release()
        } finally {
            channel.close()
        }
    }
}

private fun acquireSessionFiles(directory: Path): SessionFiles {
    // An OS lock survives screen changes and is released even by Runtime.halt/process death.
    val channel = checkNotNull(FileChannel.open(directory.resolve("owner.lock"), CREATE, WRITE))
    try {
        val lock = checkNotNull(channel.tryLock()) { "Another process owns this outbox" }
        try {
            return SessionFiles(channel, lock, DiskOutbox(directory.resolve("domain.json")), DiskLedger(directory.resolve("ledger.json")))
        } catch (failure: Throwable) {
            lock.release()
            throw failure
        }
    } catch (failure: Throwable) {
        channel.close()
        throw failure
    }
}

/** One owner/collector. Close a screen's subscription separately from this retained Store. */
class CreditSession private constructor(
    private val files: SessionFiles,
) : AutoCloseable {
    constructor(directory: Path) : this(acquireSessionFiles(directory))

    @Volatile private var closed = false
    internal val isClosing: Boolean get() = closed
    private val ownerJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + ownerJob)
    val saver = files.saver
    val ledger = files.ledger
    val failures = MutableSharedFlow<Throwable>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val store =
        MachineStore(
            creditMachine,
            Outbox.Unstarted,
            CommandHandler<Nothing, CreditAction> { _, _ -> },
            scope,
            coroutineContext = scope.coroutineContext,
            mailbox = MailboxConfig(policy = { EffectPolicy.Retained() }),
        ) {
            stateSaver(saver)
            exceptionHandler(ExceptionHandler { check(failures.tryEmit(it)) })
        }

    suspend fun start() =
        withTimeout(10_000) {
            store.start()
            saver.savedRevision.first { it > 0 }
        }

    suspend fun request(intent: CreditIntent) =
        withTimeout(10_000) {
            saver.awaitSaved({ it.pendingMatches(intent) || it.completedMatches(intent) }) {
                store.dispatch(CreditAction.Request(intent))
            }
        }

    /** Hooks are test seams at real commit boundaries; production uses the default empty hook. */
    suspend fun handle(
        delivery: Delivery<ApplyCredit>,
        boundary: (Boundary) -> Unit = {},
    ) {
        val handler =
            synchronized(this) {
                check(!closed) { "CreditSession is closed" }
                scope.async { handleOwned(delivery, boundary) }
            }
        try {
            handler.await()
        } finally {
            // Screen cancellation must also stop (and join) the retained owner's handler.
            // A blocking filesystem operation may finish despite cancellation; keep ownership
            // until it returns, so a new session cannot write alongside it.
            withContext(NonCancellable) { handler.cancelAndJoin() }
        }
    }

    private suspend fun handleOwned(
        delivery: Delivery<ApplyCredit>,
        boundary: (Boundary) -> Unit,
    ) = withTimeout(10_000) {
        val intent = delivery.event.intent
        // StateSaver failures do not abort a Actron commit. Never apply an unpersisted intent.
        if (saver.durable.value.completedMatches(intent)) {
            val receipts = ledger.read().receipts
            check(intent.id in receipts && receipts.getValue(intent.id) == intent.amount) { "Completed intent has no matching receipt" }
            check(delivery.acknowledge())
            return@withTimeout
        }
        check(saver.durable.value.pendingMatches(intent)) { "Intent is not durably pending" }
        boundary(Boundary.BeforeEffect)
        withContext(Dispatchers.IO) { ledger.apply(intent) }
        boundary(Boundary.AfterEffect)
        saver.awaitSaved({ it.completedMatches(intent) }) {
            store.dispatch(CreditAction.Applied(intent))
        }
        boundary(Boundary.AfterDomainAck)
        check(delivery.acknowledge()) { "Mailbox no longer owns the delivery" }
        boundary(Boundary.AfterMailboxAck)
    }

    // CLI teardown runs outside Store/handler jobs and waits before releasing the writer lock.
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            store.close()
        } finally {
            try {
                runBlocking { ownerJob.cancelAndJoin() }
            } finally {
                files.close()
            }
        }
    }
}
