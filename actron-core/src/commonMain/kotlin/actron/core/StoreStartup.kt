@file:OptIn(InternalActronApi::class)

package actron.core

/** Owns successful initialization phases. Requesting startup seals configuration before execution. */
internal class StoreStartup<S : State, A : Action, E : Event>(
    private val configuration: () -> StoreConfiguration<S, A, E>,
    private val inputs: StoreInputs<S, A, E>,
    private val startPlugins: suspend () -> Unit,
    private val enter: suspend () -> Unit,
    private val report: suspend (Throwable) -> Unit,
) {
    var isInitialized: Boolean = false
        private set

    // Successful onStart hooks may already have subscribed or launched work; they never retry.
    private var arePluginsStarted = false

    fun request(): InputId {
        configuration().seal()
        return inputs.requestStartup()
    }

    // Must be called only while holding `mutex`.
    suspend fun initialize() {
        if (isInitialized) return
        inputs.process(request()) {
            if (!arePluginsStarted) {
                startPlugins()
                arePluginsStarted = true
            }
            // A failed initial `enter {}` (no recover {} handled it) is reported, and the Store counts
            // as started, as after a failed `enter {}` of a later state: otherwise every later dispatch
            // would run the handler again and drop its action while it keeps failing.
            try {
                enter()
            } catch (t: StoreBoundaryFailure) {
                // The Store counts as started, but the startup did not succeed: its outcome says so.
                inputs.current.fail(t.original)
                report(t)
            } finally {
                isInitialized = true
            }
        }
    }
}
