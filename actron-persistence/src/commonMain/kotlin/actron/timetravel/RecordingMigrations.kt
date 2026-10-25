package actron.timetravel

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal fun builtInRecordingMigrations(): List<FormatMigration> =
    listOf(
        FormatMigration(from = 1, to = 2, migrate = ::initialToCheckpoint),
        // Format 3 only adds an input type; a format 2 text is a format 3 text without bridge inputs.
        FormatMigration(from = 2, to = 3) { advanceVersion(it, 3) },
        // Format 4 only adds the checkpoint's effects; a format 3 text is a format 4 text with none pending.
        FormatMigration(from = 3, to = 4) { advanceVersion(it, 4) },
        // Format 5 only adds an input type; a format 4 text is a format 5 text without external inputs.
        FormatMigration(from = 4, to = 5) { advanceVersion(it, 5) },
        // Format 6 only adds an effect policy's optional budget; a format 5 text is a format 6 text whose policies have none.
        FormatMigration(from = 5, to = 6) { advanceVersion(it, 6) },
    )

// Format 1 had an `initial` snapshot where format 2 has the `start` checkpoint of a run that
// has not started: the snapshot, the clock at zero, nothing running.
private fun initialToCheckpoint(previous: JsonObject): JsonObject =
    buildJsonObject {
        put("formatVersion", JsonPrimitive(2))
        if ("definition" in previous) put("definition", previous.getValue("definition"))
        if ("version" in previous) put("version", previous.getValue("version"))
        put(
            "start",
            buildJsonObject {
                if ("initial" in previous) put("snapshot", previous.getValue("initial"))
                put("now", JsonPrimitive("PT0S"))
            },
        )
        if ("steps" in previous) put("steps", previous.getValue("steps"))
    }

private fun advanceVersion(
    previous: JsonObject,
    version: Int,
): JsonObject =
    buildJsonObject {
        for ((key, value) in previous) put(key, if (key == "formatVersion") JsonPrimitive(version) else value)
    }
