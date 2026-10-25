package actron.logging

import actron.core.Action
import actron.core.PluginScope
import actron.core.State
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Owns log execution policy and settings, but never retains a Store scope or owns a coroutine Job. */
internal class LoggingDelivery(
    private val tag: String,
    private val severity: Logger.Severity,
    private val logger: Logger,
    private val dispatcher: CoroutineContext,
) {
    fun <S : State, A : Action> write(
        scope: PluginScope<S, A>,
        message: () -> String,
    ) {
        if (dispatcher == EmptyCoroutineContext) {
            try {
                logger.log(severity = severity, tag = tag, message = message)
            } catch (e: Exception) {
                // Preserve the boundary: an ordinary logger/rendering failure must not abort the
                // hook. Report the same exception through the Store, preserving its input origin.
                scope.launch { throw e }
            }
        } else {
            scope.launch(dispatcher) {
                logger.log(severity = severity, tag = tag, message = message)
            }
        }
    }
}
