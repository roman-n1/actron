package actron.core

/** Reports boundary failures exactly once and prevents a failing probe from tracing itself. */
@OptIn(InternalActronApi::class)
internal class StoreReporting<S : State, A : Action, E : Event>(
    private val configuration: StoreConfiguration<S, A, E>,
) {
    /**
     * Reports a recoverable [t] to the exception handler without aborting the current transition.
     * Cancellation and fatal errors are rethrown. If the handler itself throws, as
     * [ExceptionHandler.Rethrow] does, the transition is aborted and the error reaches the
     * coroutine exception handler as before.
     */
    suspend fun reportWithoutAborting(
        t: Throwable,
        input: InputAttribution,
    ) {
        if (t is StoreBoundaryFailure && t.handlerFailed) throw t
        val original = t.boundaryCause()
        rethrowIfNonRecoverable(original)
        trace { StoreTrace.FailureReported(input, original) }
        try {
            configuration.exceptionHandler.handle(original)
        } catch (handlerError: Throwable) {
            throw StoreBoundaryFailure(handlerError, handlerFailed = true)
        }
    }

    fun handleException(
        t: Throwable,
        input: InputAttribution,
    ) {
        // The handler already saw this one and threw: it is not asked again, the error escapes.
        if (t is StoreBoundaryFailure && t.handlerFailed) throw t.original
        val handled = t.boundaryCause()
        trace { StoreTrace.FailureReported(input, handled) }
        configuration.exceptionHandler.handle(handled)
    }

    inline fun trace(build: () -> StoreTrace<S, A, E>) {
        if (configuration.probes.isEmpty()) return
        record(build())
    }

    fun record(trace: StoreTrace<S, A, E>) {
        for (probe in configuration.probes) {
            try {
                probe.record(trace)
            } catch (e: Exception) {
                // Reported, never traced: a failing probe cannot recurse, and the boundary proceeds.
                configuration.exceptionHandler.handle(e)
            }
        }
    }
}
