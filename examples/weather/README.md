# Weather application

A two-screen Compose Multiplatform application for Android, Desktop and the browser.
Search for a city, open its current weather and six-hour forecast, refresh or go back.
Settings contains an optional debug history panel, shown only on the right. Recording starts
with the machines, independently of panel visibility.

## Run

```sh
./gradlew :weather-example:run
./gradlew :weather-example:run --args='--offline'
./gradlew :weather-example:jsBrowserDevelopmentRun
./gradlew :weather-android:assembleDebug
```

In Android Studio select the `weather-android` application module. The debug APK is
`examples/weather-android/build/outputs/apk/debug/weather-android-debug.apk` (Android 7.0+).
Live data uses Open-Meteo without an API key. `--offline` selects explicit local fixtures on
Desktop. The old article, replay and CLI-check modes have been removed from this application.

## Responsibilities

- **Decompose** owns the component tree, lifecycle and navigation stack. The root handles only
  `CitySelected(city)` and `BackClicked`. The weather configuration carries the selected city.
- **Actron** owns each feature's `MachineStore`, state, transitions, timers, command execution
  and cancellation. Search and weather have independent stores; weather contains two parallel regions.
- **Command handlers** call the use case/repositories and return typed result actions. Network
  access is outside the pure machine definitions.
- **Compose** reads the machine snapshot and sends screen intents. UI models are pure formatting
  projections, never independent mutable stores.

The root does not copy feature states or forward search/refresh requests. Each feature handles
its own intents. The KMP-style parent/child bridge carries navigation news only. No parent-to-child
state broadcasts are needed; the corresponding action type is `Nothing`.

```text
app/WeatherRootComponent.kt       Decompose Search / Weather(city) stack
core/component/                  Base/StackComponent, bridges, retained MachineInstance
core/mvi/RequestState.kt          Shared typed request lifecycle
feature/cities/
  data/                          HTTP and offline search repositories
  domain/                        CitySearchRepository, SearchCitiesUseCase
  presentation/                  Component, machine, command handler, screen and UI projection
feature/weather/
  data/                          Current/hourly HTTP and offline repositories
  domain/                        Weather data and repository interfaces
  presentation/                  Component, parallel machine, command handler, screen and UI projection
```

Each component retains its `MachineInstance` through Decompose's `InstanceKeeper`.
Recreating the host with its saved navigation state reuses the same executor. Returning from
weather destroys that child and cancels its requests. Search stays in the back stack with its
query and results. Opening another city creates a new weather child and executor. Merely opening
search does not request weather for a default city.

All platform entry points create `DefaultWeatherRootComponent` and call `root.Render()`.
Desktop constructs/destroys it on the Swing UI thread; Android uses the Activity component context.
Each screen owns its padding and scrolling. There is no `WeatherApp` or `WeatherAppContent` wrapper.

## State machines

`CitySearchMachine` uses `RequestState<List<CityOption>>`:
`Idle → Debouncing → Loading → Success / Error / TimedOut`.
Every edit updates the query and revision; 350 ms of quiet starts the request, even for one letter.
Clearing cancels pending work and restores `Idle`. An empty server response is `Success(emptyList())`.
Responses for outdated revisions are ignored. Requests time out after eight seconds.

`CityWeatherMachine` uses the typed parallel DSL with `Current` and `Hourly` regions, containing
`RequestState<WeatherReading>` and `RequestState<HourlyForecast>`. Each declares
`Idle`, `Loading`, `Success`, `Error` and `TimedOut`. Refresh loads both; retrying the forecast
only restarts `Hourly`. Responses, errors and timeouts are independent.

The state variants define graph identity through the DSL. There are no independent state IDs,
loading/error flags or string guards/effects. `RequestState` uses generated `@Serializable`
serialization. Strict repository null-safety checks apply to this example too.

## Data

Current weather includes temperature, apparent temperature and wind. The receipt timestamp is
shown at the upper right. The hourly request returns six hours of temperature, wind and rain
probability in the selected city's local time. Errors are shown without replacing live data
with fixtures. Weather data: Open-Meteo ([CC BY 4.0](https://creativecommons.org/licenses/by/4.0/));
city data: [GeoNames](https://www.geonames.org/).

## Verification

```sh
./gradlew :weather-example:jvmTest :weather-example:compileKotlinJs
./gradlew :weather-android:assembleDebug :nullability-guard:checkPolicy
```

Tests cover HTTP decoding, debounce and revisions, cancellation and timeouts, independent
parallel requests, generic serialization, navigation, host recreation and request cleanup.
The example integrates the library's recording/replay and inspector APIs; its host adapter
owns only Weather display frames, screen restoration and platform file access.

## Session history and replay

Every default feature executor attaches the library's `BoundedMachineRecorder` through a
`DecisionTraceObserver` before `start()`. The root retains one `WeatherHistory` across host
recreation and shares it with both feature factories. Its timeline enforces **500 entries in
total**, including closed weather screens. Evicting a timeline entry also trims that feature's
recorder with `discardBefore`, advancing its replay checkpoint. Closed feature recorders are
released when their last timeline entry disappears; indices remain stable.

Open the gear next to **Cities**, then enable **Debug panel** for the reusable library `HistoryPanel`. The global
chronology contains only machine events and groups lifetimes by stable machine ID.
Navigation stays in the example: `WeatherFrame` carries the route, and the root restores the
Decompose stack through the generic panel callback. The library treats that frame as an opaque snapshot. Each selection shows
the input action with its payload between the before/after active states, structured field
changes. Technical diagnostics remain in the recording model. Event and update timestamps
use the device's local time zone and locale.

Selecting an event immediately renders the saved application frame with inputs disabled, without calling any
repositories. Live recording continues in the background. Switching to **Live** exits preview.
**Continue here** replaces the Decompose stack and executors with the selected frame and exits preview. Search context and both parallel weather regions are restored
together. Completed requests stay completed; running read requests restart, queued commands
keep their lanes and timers keep their remaining delay. Old executors are closed; callbacks
from an earlier branch cannot modify the new frame. After successful restoration, entries after the selected event are removed. Earlier entries
and the selected event stay available; new execution extends this linear history. The original
running session is replaced. This is an at-least-once restart of read-only
work, not replay of arbitrary external side effects. Unsupported checkpoints with pending
effects or terminal command outcomes cannot be continued.

The example declares a typed `checkpointGroup` for search and optional weather checkpoints.
One `save(frame)` / `restore(text)` call handles all machines and compatibility checks.
Its host adapter maps the application route and retains branch metadata.
The root registers a versioned current checkpoint with Essenty `StateKeeper`, which the Android
host connects to saved instance state. After system-initiated process death, a new
`InstanceKeeper` restores search data, weather data and pending commands before creating children.
Decompose independently restores the screen stack. Completed reads are retained; pending HTTP
reads are issued again. Debounce and timeout clocks resume with the delay remaining at save time,
so time spent without a process is paused. Saving again after restoration is supported.

Only the latest resumable application checkpoint is saved, not the timeline or preview selection.
A transient checkpoint with unacknowledged effects/terminal commands falls back to the latest
resumable frame. Unsupported machine versions or invalid checkpoint payloads start fresh.
This is Android saved-state restoration of the task, not durable storage after force-stop or
removing the task. It restores the last saved checkpoint, not inputs accepted after that save.
The bounded city results and six-hour forecast fit the example's saved state; larger production
payloads should live on disk with only a reference in saved state.

History is memory-only and disappears on full application restart. The count limit is not a
byte quota. Selection and preview can each pin an immutable application frame after eviction.
Weather is an explicit diagnostics demo and includes `actron-timetravel-compose` on all targets.
Production integrations should isolate this dependency in their debug build.

Tests cover bounded history, pinned selection after eviction, action details, preview/continue,
restoring navigation and a pending parallel request, local timestamp formatting, and lifecycle
retention. Core restart tests verify command queues, remaining timer delays and entry hooks.


Desktop `:weather-example:run` copies its classpath into a per-run temporary snapshot before
starting. Rebuilding project JARs while the app is open therefore cannot invalidate classes
that the running app has not loaded yet. Normal exit removes the snapshot.

## MVI execution

The root uses `Dispatchers.Main` for machine processing and Decompose navigation. Search and
weather use typed `commandHandler` / `commandHandlers` to call domain services and return actions;
they never write state. Each machine commits decisions sequentially. Both screens collect
with `collectAsUiState`, explicitly separating UI collection from the machine dispatcher.
The shared history adapter is UI-confined together with navigation. See the
[library's threading and command contract](../../doc/guides/machine-mvi.md) before configuring
a background processor in a different host.

## Share a view-only recording

Enable the inspector through Settings. **Import** and **Export** are on the right of its
machine filter, not in Settings. Export suggests `Weather_yyyy-MM-dd_HH-mm-ss.actron.json`
using the local time zone. Configure the project name through
`WeatherHistory(exportSettings = HistoryExportSettings("My project"))`; the library has no
Weather-specific project name. Project name and export instant are also stored in JSON.

Import opens a separate read-only session: selecting a recorded event renders the original
search or weather screen, including pending, failed and successful requests. No stores,
HTTP requests, subscriptions, commands or timers are started by import. Existing live work
may complete independently in the background. Back to session restores the existing live
view; closing or failing an import never replaces its state or history. A file cannot be used
with Continue here. Export while viewing a file re-exports its display recording.

The viewer validates the file format, display schema, both machine versions and structural
graph declarations. Incompatible files are rejected, including changes to a graph without a
version bump. Reducer/handler behavior changes with the same graph still require an explicit
machine version bump. The file is a display recording, not a runnable test or checkpoint.

Validation:

- `:actron-timetravel:jvmTest`: round trips, original event IDs, invalid UTF-8/JSON, bounds,
  metadata, incompatible schemas/versions/graphs and redaction.
- `:weather-example:jvmTest`: screen selection, no continuation controls, live request and
  component isolation, cancelled/failed import preservation and Desktop file I/O.
- `:weather-android:connectedDebugAndroidTest`: actual system document picker save/open and
  cancellation on an Android emulator; no intercepted intents.
- Browser and macOS native file dialogs are additionally exercised manually during development.

## Automatically generated tests and recordings

[GeneratedWeatherScenarios.kt](src/commonTest/kotlin/actron/example/weather/GeneratedWeatherScenarios.kt)
shows the developer-facing `machine.scenarios(initial) { ... }` API:

- `actions`: finite search values (empty, Moscow, Kazan), refresh and forecast retry.
- `replies`: success/failure data derived from commands actually emitted by the machine.
- `rule`: old search answers cannot change context; forecast replies/retries preserve current weather.
- `goal`: require a late Moscow reply during Kazan's request, subsequent Kazan success, and
  both orders of the two parallel weather responses.

Actron discovers the order of actions, real debounce/timeout timers and command responses from
the graph and decisions. The tests do not prescribe the Moscow→Kazan sequence. They verify
both a stale command result (rejected by command identity) and a detached stale action (rejected
by the search revision guard). The test library's own deliberately broken-guard fixture proves
that the generator detects and reproduces this kind of bug.

With the checked-in data and bounds, search explores 763 decisions and selects 14 scenarios,
covering **16/16 transitions**; weather explores 59 decisions and selects 12 scenarios, covering
**21/21 transitions**. Both machines cover all declared timer transitions. This is bounded pure
model coverage, not exhaustive payload/guard coverage or verification of executor scheduling.
Existing queued-store tests continue to cover actual command execution and cancellation.

Run the tests and produce ready-to-import recordings:

```sh
./gradlew :weather-example:jvmTest --tests '*Generated*'
```

Files appear in `examples/weather/build/generated-scenarios/`:

- `late-moscow-command.actron.json`: search Moscow, switch to Kazan, ignore Moscow's late
  command result, then display Kazan's result.
- `late-moscow-action.actron.json`: the independent revision-guard check.
- `parallel-forecast-first.actron.json`: forecast succeeds while current weather is still loading.

Enable the inspector and use **Import** beside the machine filter. Select events to see the
recorded search/weather screen, before/action/after and graph coverage. The export includes
states and event details, so the ignored Moscow reply is visible even though it changes no data.
These files use Weather's existing schema/version/graph validation and remain view-only.
Importing them runs no test, command or HTTP request. Executable typed scenarios remain in the tests.
