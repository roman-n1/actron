# Weather application

A two-screen Compose Multiplatform application for Android, Desktop, iOS and the browser.
Search for a city, open its current weather, six-hour forecast and dismissible weather advice, refresh or go back.
Settings contains an optional debug history panel, shown only on the right. Recording starts
with the machines, independently of panel visibility.

## Read the example

Start with [How Weather uses Actron](docs/architecture.md): the path from UI intent to machine
decision and HTTP result, the typed search DSL, independent weather machines, component lifetime,
and the optional history inspector.

For tests, read [the test-source README](debugTools/src/commonTest/README.md). It maps the scripted,
generated and HTTP-cancellation examples to the library APIs and shows how to inspect a
generated scenario in the running application. Source comments mark the integration points;
the guides explain the contracts behind those calls.

Suggested source order:

1. [CitySearchMachine](shared/src/commonMain/kotlin/actron/example/weather/feature/cities/presentation/CitySearchMachine.kt):
   state, actions, repository lookup, transitions, debounce and cancellation.
2. [CitySearchComponent](shared/src/commonMain/kotlin/actron/example/weather/feature/cities/presentation/CitySearchComponent.kt):
   call the feature repository, dispatch actions and collect state.
3. [CityWeatherMachine](shared/src/commonMain/kotlin/actron/example/weather/feature/weather/presentation/CityWeatherMachine.kt):
   independent regions in one machine.
4. [MachineInstance](../../actron-decompose/src/commonMain/kotlin/actron/decompose/MachineInstance.kt)
   and [WeatherRootComponent](shared/src/commonMain/kotlin/actron/example/weather/app/root/WeatherRootComponent.kt):
   connect Actron execution to Decompose lifetime and navigation.
5. [GeneratedWeatherScenarios](debugTools/src/commonTest/kotlin/actron/example/weather/GeneratedWeatherScenarios.kt):
   describe test data and properties, then let the graph explorer discover sequences.

## Run

```sh
./gradlew :weather:desktopApp:run
./gradlew :weather:webApp:wasmJsBrowserDevelopmentRun
./gradlew :weather:webApp:jsBrowserDevelopmentRun
./gradlew :weather:androidApp:assembleDebug
```

The modules follow the same separation as `MyApplication`:

```text
examples/weather/
  shared/       Production Compose UI, machines and repositories
  debugTools/   Optional recorder, history UI and platform file dialogs
  androidApp/   Android Activity, manifest and device tests
  desktopApp/   JVM entry point and desktop runtime
  webApp/       Shared browser entry point, HTML, JavaScript and Wasm executables
  iosApp/       SwiftUI host, document pickers and Xcode project
```

The example uses Actron's existing Kotlin, Compose and Android plugin versions.
Android, Desktop, JavaScript, WebAssembly and iOS (arm64 device/simulator) share the same UI
and feature machines. The iOS host targets iOS 18.5+ to match the linked Skiko/ICU runtime.
Run Gradle commands from the Actron repository root.

For iOS, open `examples/weather/iosApp/iosApp.xcodeproj`, select the `iosApp` scheme and an
arm64 simulator, then Run. The Xcode build phase compiles and embeds `Shared.framework`.
Set `TEAM_ID` in `iosApp/Configuration/Config.xcconfig` for a physical device; simulator builds
do not require a signing team. Kotlin follows the application's foreground/background lifecycle;
Swift owns native document import/export. Live data uses Ktor's Darwin engine on iOS.

Web's `webMain` entry point and resources are shared by JS and Wasm. Use the Wasm run task
for modern browsers, or the JS task for the existing JavaScript target. Both retain recording
import/export through the browser's file picker and downloads.

In Android Studio select the `weather:androidApp` application module. The debug APK is
`examples/weather/androidApp/build/outputs/apk/debug/androidApp-debug.apk` (Android 7.0+).
Live data uses Open-Meteo without an API key.

## Responsibilities

- **Decompose** owns the component tree, lifecycle and navigation stack. The root handles only
  `CitySelected(city)` and `BackClicked`. The weather configuration carries the selected city.
- **Actron** owns each feature's `MachineStore`, state, transitions, timers, entry work
  and cancellation. Search and weather have independent stores; weather has a screen parent and three independent child stores.
- **Loading entries** call feature repositories and dispatch result actions. The Store cancels an entry when its state exits.
- **Presentation mappers** project machine snapshots into immutable UI models and translate screen
  intents into machine inputs or navigation news. Components execute those commands.
- **Compose** renders UI models and sends screen intents. UI models are projections, never
  independent mutable stores; machine phases, revisions and failures stay outside rendering.

The root does not copy feature states or forward search/refresh requests. Each feature handles
its own intents. The KMP-style parent/child bridge carries navigation news only. No parent-to-child
state broadcasts are needed; the corresponding action type is `Nothing`.

```text
app/root/                       Decompose Search / Weather(city) stack
app/theme/                      Shared Compose theme
app/assembly/                   Feature factories, retained machines and recording contracts
debugTools/app/debug/            Optional recording, preview and platform file adapters
core/component/                  Base/StackComponent and bridges
core/mvi/RequestState.kt          Shared typed request lifecycle
feature/cities/
  data/                          Open-Meteo search and sample data for tests/debug tools
  domain/                        CitySearchRepository
  presentation/                  Component, machine, screen and UI projection
feature/weather/
  data/                          Open-Meteo current/hourly weather and sample data for tests/debug tools
  domain/                        Weather data and repository interfaces
  presentation/                  Parent machine, screen and derived UI projection
currentweather/presentation/     Standalone current-weather state, actions and machine
hourlyforecast/presentation/     Standalone forecast state, actions and machine
weatheradvice/presentation/      Standalone advice state, actions and machine
```

Application component factories retain each `MachineInstance` through Decompose's `InstanceKeeper`.
Feature components receive their machines and expose read-only UI projections.
Recreating the host with the same InstanceKeeper reuses the same executor. Returning from
weather destroys that child and cancels its requests. Search stays in the back stack with its
query and results. Opening another city creates a new weather child and executor. Merely opening
search does not request weather for a default city.

Platform entry points construct `WeatherRootComponent` with an explicit UI context and feature factories. The root renders its own stack through `Render()`. Debug hosts compose the inspector around that rendering; the root has no rendering callback or debugger dependency.
Desktop's [main](desktopApp/src/main/kotlin/actron/example/weather/Main.kt) assembles dependencies on the UI thread, opens the window and renders Weather. It closes the root and HTTP client when the window leaves composition. Android uses the Activity component context.
Each screen owns its padding and scrolling. There is no `WeatherApp` or `WeatherAppContent` wrapper.

## Concise machine setup

A normal machine needs one call to `actron-decompose`:

```kotlin
val store = componentContext.retainedMachine(
    key = "city-search",
    machine = CitySearchMachine.create(repository),
    initial = CitySearchState.Idle(),
    coroutineContext = machineContext,
)
```

Add `recording = history.recording()` to enable recording and profiling. The option binds once, before
startup. Weather retains four stores with typed MachineGroup routes and starts the parent after the children enter Ready; the parent emits the first refresh event automatically.
The adapter owns retention/start/close and the entry scope; UI subscriptions still belong
to their component. See [the installation contract](docs/architecture.md#one-call-machine-installation).
The helper is part of `actron-decompose`; these changes have not yet been released to Maven Central.

## State machines

`CitySearchMachine` uses the sealed `CitySearchState` directly as the screen state:
`Idle → QueryChanged → Debouncing → Loading → Success / Error`.
Every edit enters `QueryChanged` and updates the query and revision once. Its ordered `always`
transitions select `Idle` for blank input, falling back to `Debouncing` otherwise, within the same decision.
350 ms of quiet starts the request, even for one letter.
Clearing cancels pending work and restores `Idle`. An empty server response is `Success(emptyList())`.
Responses for outdated revisions are ignored. The shared HTTP client sets a seven-second request deadline.

`CityWeatherMachine` owns the city header and accepts `WeatherScreenAction.Refresh`. Its `WeatherScreenState`
entry emits one `WeatherScreenEvent.RefreshChildren` on fresh startup and every Refresh self-loop.
`WeatherMachines` attaches four stores to a MachineGroup and declares three typed routes:

- RefreshChildren → `CurrentWeatherAction.Refresh` for CurrentWeatherMachine.
- RefreshChildren → `HourlyForecastAction.Refresh` for HourlyForecastMachine.
- RefreshChildren → `WeatherAdviceAction.Refresh` for WeatherAdviceMachine.

The children have their own state types, actions, cancellable entries and serialized queues.
They know neither the parent nor one another. They start in Ready without requests or timers;
then the parent's first event starts loading automatically. Current and Hourly use
Ready / Loading / Success / Error. Advice uses Ready / Loading / Hidden / Visible / Error /
Dismissed and interprets its own reading as weather tips. A global update makes two independent
current-weather calls and one hourly call. Local Hourly Retry and Advice Retry/Dismiss preserve siblings.

Every child accepts its Refresh from every state, including Loading and Dismissed. Re-entering
Loading cancels the old work and starts a new request. Group delivery is not an atomic
multi-store transaction; the immutable UI projection may briefly show mixed phases. Decompose
retains the existing machines during host recreation. A fresh process starts new requests.

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
./gradlew :weather:shared:jvmTest :weather:debugTools:jvmTest
./gradlew :weather:shared:iosSimulatorArm64Test :weather:debugTools:iosSimulatorArm64Test
./gradlew :weather:webApp:jsBrowserDevelopmentWebpack :weather:webApp:wasmJsBrowserDevelopmentWebpack
./gradlew :weather:androidApp:assembleDebug :nullability-guard:checkPolicy
```

Tests cover HTTP decoding, debounce and revisions, cancellation and request failures, independent
independent requests, typed routes, generic serialization, navigation, host recreation and request cleanup.
The optional debugTools module integrates the library's recording/replay and inspector APIs; its host adapter
owns only Weather display frames, recording and platform file access.

## Session history and replay

The machines own live feature state. Decompose owns navigation and keeps machine instances
through host recreation. After process death, Decompose can restore the route; the feature
machines start fresh and request their data again. Debug hosts wrap the ordinary root with
`RestorableComponent` from `actron-decompose-session`; restoration belongs to the library.

Debug hosts attach a `BoundedMachineRecorder` before each machine starts. `WeatherHistory`
retains up to **500 events total**, including closed weather screens. Eviction trims each
recorder too. These immutable snapshots contain display data, active graph nodes and transition
coverage. Safe positions also carry a library session checkpoint, including the Decompose route
and all live machine instances. Positions with unfinished event delivery remain preview-only.

Enable **Debug panel** from the gear next to **Cities**. Selecting an event previews that
feature with inputs disabled and without repository calls. Recording continues in the background.
**Live** returns to the running application. **Continue from here** cancels the current executors,
recreates the saved navigation tree and resumes its machines. The future branch is removed only
after successful restoration. Running read requests can restart; this does not undo remote effects.
Search and weather record separately; weather projections combine its four machines for display.
Coverage in an old event remains frozen as new events arrive.

History is memory-only and disappears on full application restart. The count limit is not a byte
quota. Selection can pin a snapshot after eviction. Android release uses the ordinary root;
Android debug, Desktop, Web and iOS add the optional inspector.

Tests cover component retention, navigation, request cancellation, fresh loads after process
recreation, bounded history, frozen coverage, preview isolation, cross-screen continuation,
serialized session restoration and view-only import/export.

Desktop `:weather:desktopApp:run` copies its classpath into a per-run temporary snapshot before
starting. Rebuilding project JARs while the app is open therefore cannot invalidate classes
that the running app has not loaded yet. Normal exit removes the snapshot.

## MVI execution

The ordinary root accepts `machineContext` and `uiContext`. Restorable debug roots run machine
decisions and navigation on the same UI dispatcher so a committed history point can capture both.
Search calls the repository directly in its cancellable Loading entry. Both feature components expose read-only UI-model StateFlows, and both screens collect those
on Main with `collectAsUiState`. Machine snapshots and the weather aggregate stay behind the
presentation boundary. `store.present(...)` connects UI-state and UI-event mappers: UI events become
business actions before dispatch, and navigation comes from committed domain outputs.
See the [session integration](../../actron-decompose-session/README.md) and [threading contract](../../doc/guides/machine-mvi.md).

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
view; closing or failing an import never replaces its state or history. Imported files expose no continuation controls. Export while viewing a file re-exports its display recording.

The viewer validates the file format, display schema, both machine versions and structural
graph declarations. Incompatible files are rejected, including changes to a graph without a
version bump. Reducer/handler behavior changes with the same graph still require an explicit
machine version bump. The file is a display recording, not a runnable test or checkpoint.

Validation:

- `:actron-timetravel:jvmTest`: round trips, original event IDs, invalid UTF-8/JSON, bounds,
  metadata, incompatible schemas/versions/graphs and redaction.
- `:weather:debugTools:jvmTest`: screen selection, no continuation controls, live request and
  component isolation, cancelled/failed import preservation and Desktop file I/O.
- `:weather:androidApp:connectedDebugAndroidTest`: actual system document picker save/open and
  cancellation on an Android emulator; no intercepted intents.
- Browser and macOS native file dialogs are additionally exercised manually during development.

## Automatically generated tests and recordings

[GeneratedWeatherScenarios.kt](debugTools/src/commonTest/kotlin/actron/example/weather/GeneratedWeatherScenarios.kt)
records an old Moscow entry completing during a new Kazan search. The machine rejects its
activation and independently rejects an outdated action by revision. A separate trace runs the
current-weather repository call. Both traces can be replayed through the pure decision function.

`CitySearchTest` checks cancellation through the real Store, repository and Ktor mock engine.
`CitySearchGraphTest` checks the ten declared edges, guards and restored timer deadlines.

Run the tests and produce ready-to-import recordings:

```sh
./gradlew :weather:debugTools:jvmTest --tests '*Generated*'
```

Files appear in `examples/weather/debugTools/build/generated-scenarios/`:

- `late-search-results.actron.json`: late Moscow entry and action followed by the Kazan result.
- `current-weather-success.actron.json`: a standalone current-weather machine reaches Success.

Enable the inspector and use **Import** beside the machine filter. Select events to see the
recorded search/weather screen, before/action/after and graph coverage. The export includes
states and event details, so the ignored Moscow reply is visible even though it changes no data.
These files use Weather's existing schema/version/graph validation and remain view-only.
Importing them runs no test, command or HTTP request. Executable typed scenarios remain in the tests.

## Temporal rules and graph profiling

[AdvancedWeatherTestingTest](debugTools/src/commonTest/kotlin/actron/example/weather/AdvancedWeatherTestingTest.kt)
demonstrates bounded temporal rules, real executor schedule exploration and mutation testing.
See the [developer guide](../../doc/guides/temporal-schedules-and-profiling.md) for API usage.

In the inspector, select an event, open **Graph**, then enable **Profile**. Click a state or
transition to inspect completed queue/processing/command samples. Weather stores immutable
measurements before each event, per executor instance; old history never follows live metrics.
Imported display recordings currently omit profiling.


The Android release graph uses only `shared`; debug adds `debugTools`. Desktop/Web and the
included SwiftUI host opt into the debugger explicitly. There is one Weather application;
the module split keeps diagnostics out of normal runtime dependencies. The production tests
verify that recorder/panel classes are absent and normal navigation and rendering still work.

UI translations use native XML resources: [localization guide](../../doc/guides/localization.md).

## Adding a feature operation

The flow is `UiEvent → toAction → MachineStore → screenMachine → domain State → toUiState → Compose`.
For a synchronous operation, add a UI event and its mapper case, a domain action, then an `on<Action>`
handler in the allowed states. For async work, enter a Loading state with `onEnter`, call the
repository there and dispatch typed success/failure actions; handle both through guarded transitions.
Keep cancellation distinct from a domain error. Refresh in Loading cancels/replaces the previous
activation; independent child machines retain their own policy.

The feature's `*Machine.kt` contains domain states/actions and transition declarations;
`*Mapper.kt` owns both UI boundaries, `*Component.kt` installs the presenter and lifecycle-bound
output observation, and `*Screen.kt` renders UI models. Debug root assembly is
`debugTools/.../RestorableWeatherRoot.kt`; generic restoration belongs to Actron.

Use pure machine tests for guards/targets and controlled repositories with `runTest` for actual
store behavior. `WeatherContinuationTest` covers replacement and encoded checkpoints;
`WeatherAppTest.continueFromSearchReplacesWeatherAndAcceptsNewInput` clicks the real history button.
See [architecture](../../doc/architecture/mvi-state-machine.md),
[audit and measurements](../../doc/architecture/api-audit.md) and
[migration](../../doc/architecture/migration-guide.md).
