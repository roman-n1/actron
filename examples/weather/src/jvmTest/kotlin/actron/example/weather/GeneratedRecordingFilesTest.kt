package actron.example.weather

import actron.example.weather.core.debug.WeatherRecording
import java.io.File
import kotlin.test.Test

/** Import these output files in Weather's inspector to inspect automatically discovered paths. */
class GeneratedRecordingFilesTest {
    @Test fun writesImportableSearchAndParallelWeatherScenarios() {
        val directory = File("build/generated-scenarios").apply { mkdirs() }
        val search = GeneratedWeatherScenarios.search()
        val searches = search.generate(maxDepth = 6, maxDecisions = 100_000)
        searches.assertSuccess()
        val weather = GeneratedWeatherScenarios.weather()
        val forecasts = weather.generate(maxDepth = 5, maxDecisions = 50_000)
        forecasts.assertSuccess()
        val recordings =
            mapOf(
                "late-moscow-command" to searchRecording(search.record(searches.witnesses.getValue("kazan-success-after-late-moscow"))),
                "late-moscow-action" to searchRecording(search.record(searches.witnesses.getValue("late-moscow-action"))),
                "parallel-forecast-first" to weatherRecording(weather.record(forecasts.witnesses.getValue("forecast-before-current"))),
            )
        for ((name, recording) in recordings) {
            val file = File(directory, "$name.actron.json")
            val bytes = WeatherRecording.codec.encode(recording)
            WeatherRecording.import(bytes)
            file.writeBytes(bytes)
            println("Generated scenario recording: ${file.absolutePath}")
        }
    }
}
