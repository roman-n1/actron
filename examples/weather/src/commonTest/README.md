# Testing the Weather machines

[Example README](../../README.md) · [Architecture](../../docs/architecture.md) ·
[Actron test API](../../../../actron-statechart-test/README.md)

This directory contains both tests of the pure decision model and tests of the real queued
executor. Choose the level that matches the behavior being checked. HTTP tests use Ktor
MockEngine; none of these tests needs the public weather server.

## Where to start

| File | What it demonstrates |
| --- | --- |
| [CitySearchScenarioTest](kotlin/actron/example/weather/CitySearchScenarioTest.kt) | Handwritten `machine.test` scenario, virtual debounce, command answers and assertions |
| [WeatherScenarioTest](kotlin/actron/example/weather/WeatherScenarioTest.kt) | Select which parallel command completes; preserve the other region |
| [GeneratedWeatherScenarios](kotlin/actron/example/weather/GeneratedWeatherScenarios.kt) | Finite input data, command answers, rules and required goals |
| [GeneratedWeatherScenariosTest](kotlin/actron/example/weather/GeneratedWeatherScenariosTest.kt) | Generate, replay, measure coverage and project traces into display recordings |
| [CitySearchTest](kotlin/actron/example/weather/CitySearchTest.kt) | Real Store/handler/repository chain, HTTP decoding, cancellation and late callbacks |
| [WeatherMachinesCoverageTest](kotlin/actron/example/weather/WeatherMachinesCoverageTest.kt) | Merge actual transition coverage from executor scenarios |
| [GeneratedRecordingFilesTest](../jvmTest/kotlin/actron/example/weather/GeneratedRecordingFilesTest.kt) | JVM file-writing adapter for generated view-only recordings |

## Write a specific scenario

Use this when the exact ordering or execution behavior matters. Inside `runTest`, the DSL
creates a `MachineTestDriver` with a real queued Store, scripted commands and a virtual clock.

```kotlin
CitySearchMachine.create().test(this, CitySearchState()) {
    dispatch(CitySearchAction.ChangeQuery("London"))
    expectState<RequestState.Debouncing> { it.phase }
    advanceTimeBy(350.milliseconds)
    expectState<RequestState.Loading> { it.phase }
    completeCommand { command ->
        CitySearchAction.Found(command.revision, emptyList())
    }
    expectState<RequestState.Success<List<CityOption>>> { it.phase }
}
```

`dispatch` waits for the action to be processed. `advanceTimeBy` moves virtual time rather than
sleeping. `completeCommand` supplies a typed result for the matching actual command; it does
not call HTTP. In a parallel scenario use `matching = { it is WeatherCommand.Hourly }` so the
test does not accidentally answer the current-weather request. The DSL checks unexpected
failures/pending work and closes the driver. Use `allowPendingWork` only when ending with work
in progress is intentional.

## Let the graph discover scenarios

`GeneratedWeatherScenarios.search()` declares a model; it does not start a Store or HTTP client.
It calls `machine.scenarios(initial) { ... }` with four kinds of declarations:

| API | Developer supplies | What Actron does |
| --- | --- | --- |
| `actions` | Finite payload choices; Weather limits query edits to two | Select declared action types at the active nodes |
| `replies` | Success/error payloads for the actual command | Preserve command identity/revision and explore result choices |
| `rule` | A property of before/input/after that must always hold | Check each explored decision, including ignored inputs |
| `goal` | A situation that must occur on at least one path | Keep a concrete witness, or report the goal as missing |

The graph and actual decisions provide transitions, command registrations and timers. The
generator chooses their order. Weather supplies empty/Moscow/Kazan queries, not a prescribed
Moscow→Kazan script.

```kotlin
val model = GeneratedWeatherScenarios.search()
val generated = model.generate(maxDepth = 6, maxDecisions = 100_000)
generated.assertSuccess()

val scenario = generated.witnesses.getValue("kazan-success-after-late-moscow")
val trace = model.record(scenario)
trace.assertSuccess()
println(trace.coverage.describe(model.machine.chart))
```

`generate` is bounded pure-model exploration using the real decision function. It does not
test coroutine scheduling, HTTP behavior or executor lane arbitration. Its `assertSuccess`
rejects rule failures, budget exhaustion, uncovered target transitions and missing goals.
With the current data, the selected scenarios cover 16/16 search and 21/21 weather transitions.
This does not imply every guard outcome, payload or longer sequence was tested.

`record` reruns the discovered inputs from the same initial context and retains each actual
before/input/after decision. On failure, `generated.exploration.failures` contains the exact
input prefix and state; use `model.record(MachineScenario("regression", failure.inputs))` to
reproduce it with the same rules. Keep generators and reducers deterministic and data immutable.

### Why test both cancellation and a late reply?

Changing the query must cancel the old command immediately, before waiting for the next
350 ms debounce. The generated rule asserts old command removal and its cancelled scope.
`CitySearchTest` additionally checks cancellation reaching a suspended Ktor MockEngine request.

Late-result tests then check defense against races: `LateCommand` injects a result with an old
command ID; `LateAction` injects an old payload directly to check the query revision guard.
Neither keeps the cancelled HTTP request alive. The goal requires the old Moscow response to
arrive while Kazan is loading; otherwise a stale-response rule could pass without ever seeing
a stale response. Another goal requires Kazan to succeed afterwards.

## View the discovered sequence in the inspector

`searchRecording(trace)` / `weatherRecording(trace)` show the application adapter. They call
`trace.recording` from the optional `actron-timetravel-test` module, serialize context fields,
and supply a `WeatherRecordingFrame` with screen data, active nodes and transition hits.
Weather's existing schema and graph definitions let its import codec validate compatibility.
The exporter retains ignored events too: a stale response appears even though its before/after
states are equal.

From the repository root:

```sh
./gradlew :weather-example:jvmTest --tests '*Generated*'
./gradlew :weather-example:run
```

Open Settings → Debug panel → Import beside the machine filter. Choose a file under
`examples/weather/build/generated-scenarios/`:

- `late-moscow-command.actron.json`: cancelled Moscow request, stale result, Kazan success.
- `late-moscow-action.actron.json`: independent revision-guard verification.
- `parallel-forecast-first.actron.json`: forecast displayed while current weather is loading.

Scrub the timeline and switch between Event and Graph. Imported files contain display data,
not executable commands; they cannot be continued. The typed in-memory scenario is the
executable test. `GeneratedRecordingFilesTest` owns file writing, so common tests have no
platform file-picker dependency.

Run all Weather tests with `./gradlew :weather-example:jvmTest`. For library-level changes,
also run the affected `:actron-statechart-test` or `:actron-timetravel-test` target. Test API
contracts and generation limits are documented in the [library guide](../../../../actron-statechart-test/README.md).
