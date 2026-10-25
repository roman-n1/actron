package actron.logging

import actron.core.Action
import actron.core.Event
import actron.core.Plugin
import actron.core.State
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Creates a plugin that logs actions, events, and committed state changes.
 *
 * Without a [dispatcher], each entry is logged from the Store hook itself, so entries keep the
 * order in which the Store processed them. With a non-empty [dispatcher] context, each entry is
 * launched separately using that context, so entries may appear out of order. An unconfined
 * dispatcher may execute the logger immediately; supplying a context alone does not guarantee
 * offloading. Entries survive exit from their originating state. With the inherited Store Job,
 * pending entries are dropped when the Store closes or its parent is cancelled; there is no
 * drain-on-close queue. The context is passed unchanged to [actron.core.PluginScope.launch],
 * so supplying a replacement Job overrides that lifetime as in ordinary coroutine launches.
 *
 * Entries include the full `toString()` of actions, events and states. Avoid enabling this plugin
 * in release builds when those contain personal data or credentials.
 *
 * An ordinary exception thrown by [logger] (or by a `toString()`) is reported to the Store's exception
 * handler via Store-owned background work; the action, event or state change being logged is
 * processed as usual. Message rendering stays lazy: a logger that skips [Logger.log]'s message
 * provider does not evaluate `toString()`. Cancellation exceptions follow coroutine cancellation
 * semantics; fatal throwables keep the Store's existing failure boundary. Deferred providers refer
 * to the objects captured by their hook; they do not read the Store's latest state or pre-render mutable payloads.
 *
 * @param tag The tag to use for logging
 * @param severity The severity level for log messages
 * @param logger The logger implementation to use
 * @param dispatcher Execution context to log on instead of the Store's hook
 * @return Plugin that logs common Store operations
 */
fun <S : State, A : Action, E : Event> simpleLogging(
    tag: String = "Actron",
    severity: Logger.Severity = Logger.Severity.Debug,
    logger: Logger = DefaultLogger,
    dispatcher: CoroutineContext = EmptyCoroutineContext,
): Plugin<S, A, E> = SimpleLoggingPlugin(LoggingDelivery(tag, severity, logger, dispatcher))
