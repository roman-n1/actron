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

## Session history

Every default feature executor installs a `DecisionTraceObserver` before `start()`. One
`WeatherHistory` is retained by the root's `InstanceKeeper` and shared with both feature
factories. The bounded deque keeps the most recent **500 entries across all feature instances**;
a new entry evicts the oldest. Closed weather screens remain inspectable until their entries
are evicted. Clearing history releases the retained entries and recording continues.

Open **Settings → Debug panel** to inspect inputs, before/after snapshots, transitions,
commands, timers and actual guard evaluations. Ignored and failed decisions are included.
The panel observes decisions; it never redispatches an action or calls an API. Selecting an
entry displays its details without replacing the live application's state. This is history
inspection, not executor checkpoint replay. The unbounded `MachineRecorder` is deliberately
not attached; history uses the library's live decision observer contract.

History and panel visibility survive host recreation with the retained component owner.
They are held only in memory and disappear after a complete application restart. The limit
is an entry count, not a byte quota; payload size still affects memory. Tests use smaller
capacities to exercise eviction and verify that hiding/showing the panel never resets history.
