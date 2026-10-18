package actron.statechart.machine

import actron.core.Action
import actron.core.ActronStoreDsl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Executes one request and returns its action through the machine's queue. Expected service
 * failures become actions via [onFailure]; cancellation is never converted to a failure action.
 * Neither callback receives mutable store state. Blocking work must choose its own dispatcher.
 * Use [CommandHandler] directly for subscriptions producing multiple actions.
 */
fun <CMD : Any, A : Action> commandHandler(
    onFailure: (CMD, Exception) -> A,
    execute: suspend (CMD) -> A,
): CommandHandler<CMD, A> =
    CommandHandler { envelope, results ->
        val execution = currentCoroutineContext()
        execution.ensureActive()
        val action =
            try {
                execute(envelope.command)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                execution.ensureActive()
                onFailure(envelope.command, error)
            }
        execution.ensureActive()
        results.result(action)
    }

/** Typed command routing without casts, envelope unpacking, or manual result emission. */
fun <CMD : Any, A : Action> commandHandlers(
    build: CommandHandlersBuilder<CMD, A>.() -> Unit,
): CommandHandler<CMD, A> = CommandHandlersBuilder<CMD, A>().apply(build).build()

/** Registers request handlers. Exactly one registration must match each executed command. */
@ActronStoreDsl
class CommandHandlersBuilder<CMD : Any, A : Action>
    @PublishedApi
    internal constructor() {
        private val handlers = mutableListOf<Pair<(CMD) -> Boolean, CommandHandler<CMD, A>>>()

        /** Maps the successful request to an action; [onFailure] maps expected service errors. */
        inline fun <reified T : CMD> handle(
            noinline onFailure: (T, Exception) -> A,
            noinline execute: suspend (T) -> A,
        ) {
            register(
                { it is T },
                commandHandler(
                    onFailure = { command, error ->
                        check(command is T)
                        onFailure(command, error)
                    },
                    execute = { command ->
                        check(command is T)
                        execute(command)
                    },
                ),
            )
        }

        @PublishedApi
        internal fun register(
            matches: (CMD) -> Boolean,
            handler: CommandHandler<CMD, A>,
        ) {
            handlers += matches to handler
        }

        internal fun build(): CommandHandler<CMD, A> {
            val registered = handlers.toList()
            return CommandHandler { envelope, results ->
                val matching = registered.filter { it.first(envelope.command) }
                check(matching.size == 1) { "[Actron] Expected exactly one command handler, found ${matching.size} for ${envelope.command::class}" }
                matching.single().second.execute(envelope, results)
            }
        }
    }
