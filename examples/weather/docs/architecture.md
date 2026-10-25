# How Weather uses Actron

[Back to the example](../README.md) · [Testing guide](../debugTools/src/commonTest/README.md)

Weather has two screens and two machine definitions. Read the search feature first; the weather
feature applies the same integration to two parallel regions. Navigation is entirely Decompose's
responsibility. No third navigation machine is registered in Actron.

## Follow one input through the application

```mermaid
flowchart LR
    Screen[Compose screen] -->|UI intent| Component[Feature component]
    Component -->|dispatch action| Store[MachineStore queue]
    Store -->|current snapshot and input| Machine[Pure machine decision]
    Machine -->|new snapshot| Store
    Store -->|StateFlow / collectAsUiState| Screen
    Machine -->|command description| Executor[Command executor]
    Executor --> Handler[Command handler]
    Handler --> Repository[Feature repository / HTTP]
    Repository -->|typed result action| Store
```

For search, the concrete chain is:

1. `CitySearchScreen` emits `CitySearchIntent.QueryChanged`.
2. `DefaultCitySearchComponent.onUiEvent` maps it to `CitySearchAction.ChangeQuery` and calls
   `store.dispatch`. It never assigns the feature state itself.
3. `MachineStore` processes the input sequentially against its current snapshot. The machine
   returns updated data, state transitions and descriptions of commands/timers to execute.
4. `citySearchHandler` runs `CitySearchRepository`, which calls `CitySearchRepository`.
   It returns `Found` or `Failed`; the result goes back through the machine's input queue.
5. `collectAsUiState` collects snapshots for Compose. `toUiState` formats the context for display.

The root supplies `Dispatchers.Main` for this example. Suspending HTTP belongs in a handler;
reducers and guards must remain quick and deterministic. Configuring a different processor does
not make synchronous blocking work in a reducer appropriate. See the library's
[threading contract](../../../doc/guides/machine-mvi.md) for dispatcher and UI-collection details.

## Define a machine once

Start with [CitySearchMachine.kt](../shared/src/commonMain/kotlin/actron/example/weather/feature/cities/presentation/CitySearchMachine.kt).

| Declaration | Meaning in Weather |
| --- | --- |
| `CitySearchState` | Immutable context: query, revision and a typed request state |
| `CitySearchAction` | User edits and typed operation results |
| `SearchCitiesCommand` | Data describing a search to run; contains no HTTP implementation |
| `typedStateMachine` | Connects the typed request state to the library's graph/runtime |
| `readState` / `writeState` | Select `context.phase` and copy its new value into the context |
| `state<T>` | Declares the transitions/handlers available in that state |
| `on<Action, Target>` | Optional guard/reducer plus construction of the target state |
| `after<Target>` | A timer owned by this state's current activation |
| `onEnter { command(...) }` | Request work when entering the state; execute it outside the decision |

`phase` holds the state value. There is no second handwritten `StateId` field to synchronize.
The DSL derives graph nodes from state declarations. `RequestState.Success` carries results,
`Error` carries an error message; loading and error booleans are not independently stored.
`MachineSnapshot` adds runtime configuration, revision and command/timer identities around the
context. The context's search `revision` identifies the query generation; it is distinct from
that runtime snapshot revision.

`DefinitionId` identifies a logical machine across its instances. `DefinitionVersion` is an
explicit compatibility contract for saved state/recordings. Bump it for incompatible behavioral
or data changes, including changes that do not alter the graph's visible structure. Region and
lane names are structural identifiers, not string keys for guards or effects.

### Search, cancellation and late answers

```text
Idle → ChangeQuery("Москва") → Debouncing → after 350 ms → Loading
Loading → ChangeQuery("Казань") → Debouncing → after 350 ms → Loading
```

The second edit updates the query/revision and exits the old `Loading` activation. Its command
and timeout are cancelled immediately, before the next debounce expires. Further edits restart
the debounce. Clearing enters `Idle`, with no pending search and no displayed results.

The new `Loading` entry creates a new command. The named lane uses `ConcurrencyPolicy.Latest`;
state-activation ownership also cancels work when the state exits. Timeout enters `TimedOut`.
Cancellation propagates through the command handler to the suspending repository/HTTP call;
`commandHandler` rethrows cancellation instead of converting it into `Failed`.

Stale-command identity checks and the `Found`/`Failed` revision guards are an additional defense
for already queued results or callbacks outside normal command delivery. A test that deliberately
injects an old result does not mean Weather intentionally leaves obsolete requests running.

### Parallel weather

[CityWeatherMachine.kt](../shared/src/commonMain/kotlin/actron/example/weather/feature/weather/presentation/CityWeatherMachine.kt)
uses `parallelStateMachine`. `Current` selects `context.current`; `Hourly` selects `context.hourly`.
Each region declares its own Idle/Loading/Success/Error/TimedOut states, command and timeout.
The region writers copy only their own field, preserving the other region.

`Refresh` is handled by both regions in one machine decision. `RetryForecast` is declared only
in `Hourly`. HTTP results can arrive in either order; the machine still decides inputs one at a
time. Parallel regions represent concurrently active behavior, not concurrent writes to state.

## Components and lifetime

[WeatherRootComponent.kt](../shared/src/commonMain/kotlin/actron/example/weather/app/WeatherRootComponent.kt)
creates the Decompose stack and feature factories. Selection/back events travel through the
example's `ParentBridge`/`BridgeEndpoint` to the root; they are not Actron actions controlling
navigation. `BaseComponent.Render()` and `StackComponent` render the active child.

[MachineInstance.kt](../../../actron-decompose/src/commonMain/kotlin/actron/decompose/MachineInstance.kt)
owns the Store and its coroutine scope, starts it after construction and closes/cancels it on
destruction. It has no knowledge of Weather, recording, profiling or restoration.
[WeatherComponentFactories.kt](../shared/src/commonMain/kotlin/actron/example/weather/app/WeatherComponentFactories.kt)
uses [retainedMachine](../../../actron-decompose/src/commonMain/kotlin/actron/decompose/RetainedMachine.kt)
to retain these owners through each child's `InstanceKeeper` and injects only the Store into the
feature component. Recording is an optional, lazy configuration supplied by
[WeatherMachineRecording](../debugTools/src/commonMain/kotlin/actron/example/weather/app/debug/WeatherMachineRecording.kt).
The adapter binds it before startup and chooses fresh creation or checkpoint resumption. The search child
stays on the back stack while weather is open; popping weather destroys that weather executor.
A restored weather component does not dispatch a fresh `Refresh` over the restored state.

## Recording, preview and restoration

These are different representations with different capabilities:

| Representation | Contains | Used for |
| --- | --- | --- |
| `MachineSnapshot<C>` | Context and runtime configuration/identities | Current state and pure decisions |
| `ExecutorCheckpoint<C, CMD>` | Snapshot plus work/timing needed by the executor | Restarting pending operations |
| `WeatherFrame` | Search/weather checkpoints, route and coverage | Live history preview and continuation |
| `WeatherRecordingFrame` | Display data, active nodes and coverage | Imported view-only history |

[WeatherHistory.kt](../debugTools/src/commonMain/kotlin/actron/example/weather/app/debug/WeatherHistory.kt)
attaches `DecisionTraceObserver` before the store starts. `BoundedMachineRecorder` retains the
machine trace; `HistoryTimeline` combines instances into a session-wide limit of 500 entries.
The host supplies field projections and a `WeatherFrame`; Actron does not interpret its route.

Selecting a history entry renders its saved data without running commands. Continuing from a
resumable live entry calls the root's `continueFrom`: it replaces the Decompose children and
recreates executors from their checkpoints. Pending read requests restart; completed reads stay
completed; timers retain their remaining delay. This does not restore a suspended call stack or
guarantee exactly-once external side effects. Later history is removed after successful continuation.

[WeatherSavedState.kt](../shared/src/commonMain/kotlin/actron/example/weather/app/session/WeatherSavedState.kt)
declares `checkpointGroup` with one search checkpoint and zero or one weather checkpoints.
`machinePersistence` validates/serializes each machine. The host maps those checkpoints into its
frame and registers the encoded result with Essenty `StateKeeper`; Android supplies the saved
instance-state transport. Only the latest resumable frame is saved, not the debug timeline.

[WeatherRecording.kt](../debugTools/src/commonMain/kotlin/actron/example/weather/app/debug/WeatherRecording.kt)
uses `HistoryRecordingCodec` with the display schema and both graph definitions. Import checks
schema, machine versions and graph compatibility before displaying data. `HistoryRecordingPanel`
has no continuation path. Platform `HistoryFiles` adapters open/save bytes; the library has no
Android or Decompose dependency. File import never creates a feature component or HTTP command.

## Which Actron modules are involved?

| Module | Role here |
| --- | --- |
| `actron-statechart` | Machine DSL, decisions, queued Store, commands and timers |
| `actron-compose` | UI-safe collection of store snapshots |
| `actron-persistence` | Checkpoint persistence and resumption |
| `actron-timetravel` | Recording, bounded history and display codecs |
| `actron-decompose` | Retained machine setup and component-bound observation |
| `actron-timetravel-compose` | Reusable history/graph inspector and resizing |
| `actron-statechart-test` | Scripted executor tests, pure graph exploration and coverage |
| `actron-timetravel-test` | Optional test-trace export to view-only recordings |

Weather intentionally demonstrates debug tooling on every target. In a production app, isolate
debug modules in debug/test dependencies. The reusable library owns machine behavior; the host
owns navigation, lifecycle adapters, display projections and platform file access.


## Cohesion and the KMP Messenger comparison

This comparison uses the local `kmp_messenger_1` sources inspected on 2026-10-25:
`core/navigation` (`BaseComponent`, `StackComponent`, `Bridge`), Home's child bridges/factories,
and `ContactDetailFeatureGraph` under `feature/contact-detail/impl`.

| Boundary | KMP Messenger | Weather |
| --- | --- | --- |
| Navigation | Decompose components, parent-owned bridges, child factories | The same pattern for search and weather; root handles selection/back |
| Feature state | A feature owns its state and exposes a component contract | Each Actron Store is the only live feature-state writer; UI models are projections |
| Creation and lifetime | Factories/feature graphs assemble dependencies; lifecycle releases component work | App factories retain Store owners; components cancel only their own bridge scope |
| Diagnostics | Shell/tooling integrations sit outside feature contracts | Recording, preview and file adapters live in `debugTools`; current checkpoints and persistence live in `shared/app/session` |
| Dependency size | Multiple feature API/implementation modules and substantial feature graphs | One production module, an optional debug module and two feature factories; no DI framework |

The reusable `core` has no dependency on either feature or application composition. Feature
presentation does not depend on `WeatherHistory`, application recording types or the sibling
feature. Its component receives its Store and a bridge endpoint. The search toolbar receives
an explicit composable settings slot; the app shell owns settings visibility. There is no diagnostic
composition-local to discover from feature code.

App factories deliberately know both feature implementations and the shared recording session:
that is the composition boundary. Repository calls and their result/error mapping stay in each
feature's command handler. The former `SearchCitiesUseCase` only delegated to the repository
and added no policy, so it was removed. Introduce a use case if a distinct business rule or
cross-repository operation actually appears.

`WeatherHistory` remains an application-wide recording adapter because a complete frame must
include both feature checkpoints and the current route. These are immutable recorded projections,
not independently writable live screen states. This refactoring does not redesign its timeline,
branch and checkpoint algorithms. Recording schema `actron.weather.display/2` uses the new app
package names; previous exported files are intentionally unsupported.

The scale trade-off is explicit: Messenger's separate feature graphs are useful with many
consumers and implementations; copying them into this two-screen example would add boundaries
without reducing current knowledge. Split the example's feature modules when they acquire
independent consumers or dependencies, rather than to reproduce the production module count.


## One-call machine installation

`actron-decompose` provides the machine installation API:

```kotlin
val store = componentContext.retainedMachine(
    key = "city-search",
    machine = CitySearchMachine.create(),
    initial = CitySearchState(),
    handler = citySearchHandler(repository),
)
```

No explicit scope, `getOrCreate`, `start` or close callback is needed. The default processor
is `Dispatchers.Main`; supply `coroutineContext` for a different owning dispatcher or tests.
A stable key identifies a Store for the lifetime of that `InstanceKeeper`. Recreating a component
with that key returns the original Store and does not replace its dependencies. Distinct business
instances require distinct keys (or, as in Weather, distinct child keepers).

Diagnostics and checkpoint continuation are independent options on the same call:

```kotlin
recording = history.recording()
// For the weather feature: recording = history.recording()
```

Each option is bound only when a new retained Store is created. Restoration supplies checkpoints,
clock and tracking; recording supplies diagnostic observers and profiler instrumentation. The adapter attaches them before
startup, so the first decision is recorded. Recreating a host does not bind another recorder.
Handlers/options must not retain a component, bridge endpoint or UI-lifecycle scope.

`initialActions = listOf(WeatherAction.Refresh)` starts a fresh weather screen. These actions
are dispatched after configuration/start exactly once for that retained instance, and skipped
when resuming an already-started checkpoint. Existing completed regions are not refreshed by
host recreation or continuation. Destroying the keeper closes the Store and cancels its commands.

A single `actron-decompose` module exposes the standard machine/Store integration. It depends
on core/statechart and Decompose, and has no time-travel, persistence-codec or Compose dependency.
