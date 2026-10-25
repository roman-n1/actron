package com.google.firebase.crashlytics

/** Reads captured SDK report data in its package; no reflection or global Crashlytics state. */
internal fun CustomKeysAndValues.reportKeys(): Map<String, String> {
    val values: Map<String, String> = checkNotNull(keysAndValues)
    return values.toMap()
}
