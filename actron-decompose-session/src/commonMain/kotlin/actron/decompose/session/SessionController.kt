package actron.decompose.session

import actron.statechart.machine.CutListener
import actron.statechart.machine.GroupCut
import actron.statechart.machine.MachineClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Coordinates a root's machines and its application-supplied navigation adapter. */
internal class SessionController<R : Any>(
    private val schema: SessionSchema<R>,
    private val navigation: SessionNavigation<R>,
    private val context: CoroutineContext = Dispatchers.Main,
    private val clock: MachineClock = MachineClock.monotonic(),
    deferStartup: Boolean = false,
) {
    private val operations = Mutex()
    private var closed = false
    var session: MachineSession<R> = MachineSession(schema, context, clock, active = !deferStartup)
        private set

    suspend fun checkpoint(): SessionCheckpoint =
        withContext(context) {
            operations.withLock {
                check(!closed) { "Session controller is closed" }
                navigation.pause()
                val captured = CompletableDeferred<SessionCheckpoint>()
                val listener =
                    CutListener { cut ->
                        check(cut.inFlight.isEmpty()) { "Session has bridge messages in flight" }
                        captured.complete(schema.checkpoint(navigation.snapshot(), session.encode(cut.members)))
                    }
                session.group.onCut(listener)
                try {
                    check(session.group.checkpoint() is GroupCut.Ready) { "Session did not settle for a checkpoint" }
                    captured.await()
                } finally {
                    session.group.removeCutListener(listener)
                    navigation.resume()
                }
            }
        }

    suspend fun restore(checkpoint: SessionCheckpoint) =
        withContext(context) {
            operations.withLock {
                check(!closed) { "Session controller is closed" }
                schema.validate(checkpoint)
                val routes = schema.routes(checkpoint)
                val replacement = MachineSession(schema, context, clock, checkpoint.document.machines, active = false)
                navigation.pause()
                runCatching {
                    session.closeAndJoin()
                    check(!closed) { "Session controller was closed during restoration" }
                    navigation.replace(routes, replacement)
                    replacement.verifyRestored()
                    session = replacement
                    replacement.activate()
                    navigation.resume()
                }.onFailure {
                    withContext(NonCancellable) { replacement.closeAndJoin() }
                }.getOrThrow()
            }
        }

    suspend fun close() =
        withContext(context) {
            operations.withLock {
                closed = true
                session.closeAndJoin()
            }
        }

    fun closeNow() {
        closed = true
        session.closeNow()
    }
}
