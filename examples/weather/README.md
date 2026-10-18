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
The Actron library retains its own recording/replay facilities and tests; the weather application
has no recording coordinator or debug UI dependency.

## Session history and replay

Every default feature executor attaches the library's `BoundedMachineRecorder` through a
`DecisionTraceObserver` before `start()`. The root retains one `WeatherHistory` across host
recreation and shares it with both feature factories. Its timeline enforces **500 entries in
total**, including closed weather screens. Evicting a timeline entry also trims that feature's
recorder with `discardBefore`, advancing its replay checkpoint. Closed feature recorders are
released when their last timeline entry disappears; indices remain stable.

Open the gear next to **Cities**, then enable **Debug panel** for the reusable library `HistoryPanel`. The global
chronology includes navigation and can be filtered by machine instance. Each selection shows
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
