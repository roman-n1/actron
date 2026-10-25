package actron.logging

import kotlin.concurrent.Volatile
import co.touchlab.kermit.Logger.Companion as Kermit

/**
 * Default [Logger] implementation backed by Kermit.
 */
object DefaultLogger : Logger {
    @Volatile
    private var isDisabled = false

    /**
     * Emits a log entry unless this logger has been disabled.
     */
    override fun log(
        severity: Logger.Severity,
        tag: String,
        message: () -> String,
    ) {
        if (isDisabled) return
        when (severity) {
            Logger.Severity.Verbose -> Kermit.v(tag = tag, message = message)
            Logger.Severity.Debug -> Kermit.d(tag = tag, message = message)
            Logger.Severity.Info -> Kermit.i(tag = tag, message = message)
            Logger.Severity.Warn -> Kermit.w(tag = tag, message = message)
            Logger.Severity.Error -> Kermit.e(tag = tag, message = message)
        }
    }

    override fun log(
        severity: Logger.Severity,
        tag: String,
        throwable: Throwable,
        message: () -> String,
    ) {
        if (isDisabled) return
        when (severity) {
            Logger.Severity.Verbose -> Kermit.v(throwable = throwable, tag = tag, message = message)
            Logger.Severity.Debug -> Kermit.d(throwable = throwable, tag = tag, message = message)
            Logger.Severity.Info -> Kermit.i(throwable = throwable, tag = tag, message = message)
            Logger.Severity.Warn -> Kermit.w(throwable = throwable, tag = tag, message = message)
            Logger.Severity.Error -> Kermit.e(throwable = throwable, tag = tag, message = message)
        }
    }

    /**
     * Disables this logger for the rest of the process.
     */
    @Suppress("unused")
    fun disable() {
        isDisabled = true
    }
}
