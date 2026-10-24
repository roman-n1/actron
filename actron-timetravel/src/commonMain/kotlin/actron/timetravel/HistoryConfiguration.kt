package actron.timetravel

/** Debug-only history bounds, selected once at startup. Capacity counts entries, not bytes.
 * To exclude debugging from release, omit the time-travel artifacts and observers from that build.
 */
data class HistoryConfiguration(
    val capacity: Int = 500,
) {
    init {
        require(capacity > 0) { "[Actron] History capacity must be positive" }
    }
}
