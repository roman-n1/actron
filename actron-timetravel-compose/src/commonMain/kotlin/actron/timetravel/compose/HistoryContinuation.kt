package actron.timetravel.compose

/** Host-owned restoration capability. Messages are user-facing and localized by the host. */
sealed interface HistoryContinuation {
    /** Describes application-specific effects of restoration, such as restarting unfinished reads. */
    data class Available(
        val consequences: String,
    ) : HistoryContinuation

    /** Explains the actual checkpoint restriction without guessing from display fields. */
    data class Unavailable(
        val reason: String,
    ) : HistoryContinuation
}
