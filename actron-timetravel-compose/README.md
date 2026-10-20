# actron-timetravel-compose

The debug screen for recorded Machines: select a position, move every group member together,
compare decisions, or experiment in a branch. Choose `GroupTimeTravelControls` for the whole
replay → experiment → return workflow, `GroupReplayControls` for replay only, or
`ReplayControls` for one Store. Add this to a debug dependency graph; setup follows the
[installation guide](../README.md#installation).

## Application history panel

`HistoryPanel(timeline, state, onContinue)` displays a bounded, session-wide
`HistoryTimeline<S>` across machine instances. It provides a machine filter, chronological
inputs, expandable action payloads, before/after states and field changes.
Register each lifetime with `timeline.machine("City Weather", stableId = "city-weather")`.
Use the same stable ID across recreations of one logical machine and distinct IDs for unrelated
machines, even when their display names match. The default stable ID is the supplied name.
The filter groups all lifetimes by this ID and shows only their display name; instance IDs
remain available in recorded entries. Continuing from history preserves the logical filter.
UI labels follow the device language: Russian and English are supported, with English as the fallback. Machine names, payloads and diagnostic data remain verbatim. `formatHistoryTime` uses the device locale and local time zone;
Android also respects the system 12/24-hour preference.

The application creates the timeline before starting executors, supplies immutable snapshots
and structured `StateField` values (or `historyFields(JsonElement)`), and owns restoration.
`HistoryPanelState` pins selected and preview entries even after ring-buffer eviction.
Selecting an entry immediately changes `state.preview`, which the host renders
without interactive handlers. Switching to Live clears preview. Continue here invokes the host's
callback for the selected preview; `canContinue` can reject unsupported checkpoints. The library records only machine events and has no screen or navigation model. The host owns
navigation, cancellation and safe executor restart. The weather example demonstrates this
using `resumeMachineStore` and read-only weather requests.

The timeline is bounded by entry count, not bytes. It stores no files and does not automatically
capture machines. Keep snapshots immutable and install recording even when the panel is hidden.
The panel should receive bounded layout constraints (for example a right-hand app sidebar).

## Interactive machine graph

The event list stays visible while the detail area switches between **Event** and **Graph**.
Supply typed graph data for each selected entry:

```kotlin
HistoryPanel(timeline, panelState, onContinue = ::resume, graph = { entry ->
    HistoryGraph.Machine(machine.chart, entry.snapshot.configuration.active.toSet())
})
```

For multiple machine definitions, resolve the definition using `entry.machine.stableId` and
read active IDs from that entry's immutable snapshot. Return `HistoryGraph.Unavailable` for
entries without a definition (also the default). The weather example provides both search
and parallel weather graphs. The graph always describes the **selected event**, even when
Live mode lets the application run independently.

The renderer uses Compose Canvas, with no WebView or graph-engine dependency. Compound and
parallel containers retain declaration order; local transitions stay in their own container.
Routes use separate ports and lanes. Crossing edges have a background gap; arbitrary graphs
can still have crossings. Horizontal action badges are measured before layout; long text wraps
and containers grow to reserve the full label width and height. No action label is omitted or
ellipsized. Parallel regions reserve separate label columns for cross-region transitions.
Click an action badge to isolate its exact transition and endpoints, including when several
transitions have the same name. The detail area shows source, target, trigger, guard, effect
and transition kind. Selecting a state instead shows all its outgoing actions.
This is a diagram of declared transitions, not a prediction that every guard will pass.

Drag or scroll to pan; pinch, Ctrl/Command + scroll, or use +/− to zoom (10–400%). **Fit**
centers the whole diagram. Zoom is anchored at the pointer and preserved per logical machine
across history selection and tabs while the panel remains mounted. Active IDs are highlighted
from the selected checkpoint; graph inspection never dispatches actions or resumes execution.

### macOS desktop trackpad

Touch pinch and native macOS trackpad magnification are separate input paths. The JVM renderer
registers the JDK's `MagnificationListener` on the containing Swing root pane while the graph
is mounted and removes it on disposal. Only gestures over the graph affect its viewport.
For a modern JDK, a macOS host application must export that platform API at startup:

```kotlin
compose.desktop.application {
    if (System.getProperty("os.name", "").startsWith("Mac")) {
        jvmArgs += "--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED"
    }
}
```

Weather already sets this flag. Other targets do not load Apple classes. Without the export,
zoom buttons and Ctrl/Command-scroll remain available; the native listener is not installed.

## Embeddable panel

`TimeTravelPanel` is a compact alternative to the full inspector. It has one machine picker,
back/next/verify controls, a history and expandable details, with no tabs or app navigation.
The host owns its placement and size. Wide bottom docks use horizontal history; tall, narrow
sidebars use vertical history. Navigation stays visible while details scroll. It follows the
host's `MaterialTheme`, including dark mode.

```kotlin
// Bottom dock: keep the application visible above it.
Column(Modifier.fillMaxSize()) {
    AppPreview(Modifier.weight(1f))
    TimeTravelPanel(inspector, timeTravel = controls, modifier = Modifier.height(300.dp))
}

// The same panel to the right (swap the children to put it on the left).
Row(Modifier.fillMaxSize()) {
    AppPreview(Modifier.weight(1f))
    TimeTravelPanel(inspector, timeTravel = controls, modifier = Modifier.width(320.dp).fillMaxHeight())
}
```

Overloads accept an `InspectorState` alone for live/recorded inspection, a `ReplayControls`
for one machine, a `GroupReplayControls` for group replay, or a `GroupTimeTravelControls`
for replay and isolated experiments. Filtering changes displayed history, never the group
cursor. In an experiment the panel exposes application-supplied actions and command answers,
completion/failure and virtual clock controls. Replay navigation returns with its original
position and verification result intact.

The panel does not open a window, start a recorder, replace the application's state or call
command handlers. Install recording before the bug happens. Drive an explicit, IO-free app
preview from the selected replay/branch snapshots; the [MVI weather demo](../examples/weather/README.md)
demonstrates application-frame preview and continuation with the newer HistoryPanel.

For a complete runnable recording → disk → replay → experiment workflow, see the
[Time Travel desktop example](../examples/time-travel/README.md).

A Compose Multiplatform screen over the [actron-timetravel](../actron-timetravel/README.md)
inspector: the debug UI of the
[Time Travel handoff](../doc/internal/design/2026-09-29-time-travel-logging-handoff.md) §11, for
debug builds only. It shows what `Inspector` computed and moves a `ReplaySession`; it runs no
Store and reaches no network.

- **`InspectorScreen(state)`**: the mode and the group's completeness on top (Live, Inspect,
  Replay and Branch in their own colours), the Stores with capability, counts, recording status,
  completeness reasons and why a replay is unavailable on the left, the timeline in the middle
  (a click narrows it to a Store, a click selects a position), the selected position on the
  right: input and cause, outcome, active nodes, the snapshots before and after with what
  changed when a recording is attached, commands, timers, events, failures. Stacked on a
  narrow screen.
- **`InspectorState`**: the inspector, the selection, the Store filter and the mode, as Compose
  state; `rememberInspectorState(inspector)` for a history, `rememberLiveInspector(session)`
  for a running session, rebuilt every half second.
- **`ReplayControls(store, session)`** and the replay bar: back, forward, seek, verify; a
  disabled button says why (the end of the recording, a divergence). The position panel then
  shows the machine's definition as Mermaid with the replay's active states highlighted
  (`toMermaid(active)`), selectable for pasting into a renderer.
- **`GroupReplayControls(session)`**: one cursor for all recorded machines, with back,
  forward, seek and group verification. The timeline follows `GroupRecording.order`;
  filtering by Store only changes the displayed rows. The detail panel shows the selected
  input's recorded before/after snapshots and every member's current snapshot, executor
  checkpoint and active states. A divergence identifies the failing input and member and
  stops forward movement before changing their state.
- **`BranchControls(branch, machines, inputs)`** and the branch panel: every member's
  snapshot, clock, awaiting and queued commands with buttons to complete, fail or answer
  them, the scripted `BranchInput`s the application allows (a `Dispatch`, a `Feed`, an
  `Answer` with a label), the clock's advance, the definition with the active states, and
  every decision made with what it changed. The branch decides with the pure machines;
  nothing runs.
- **`GroupTimeTravelControls(replay, routes, inputs)`**: the complete replay/experiment
  workflow. Branch Here creates an isolated branch from every member's checkpoint at the
  current global position. The screen switches to Branch mode, shows the origin and pauses
  replay navigation. Return to Replay discards the experiment and restores the replay UI at
  the original cursor, including any divergence and verification result.

Status: **experimental**, `@ExperimentalActronApi`, in the fork [roman-n1/actron](https://github.com/roman-n1/actron).

## Dependency

```kotlin
// debug source set only: this module must not be in a release dependency graph
implementation("io.github.roman-n1:actron-timetravel-compose:5.0.0-alpha.1")
```

The library's CI runs `checkDebugGraph`: no production module of actron depends on
`actron-timetravel` or this module, so what an app keeps in a debug source set stays there.

## Quick start

```kotlin
@Composable
fun DebugInspector(session: RecordingSession, recordings: Map<StoreInstanceId, Recording<*, *, *, *>>) {
    val state = rememberLiveInspector(session, recordings)        // mode Live, refreshed as the journal grows
    InspectorScreen(state)
}

@Composable
fun ReplayOf(recording: Recording<Ctx, Act, Cmd, Ev>, journal: JournalFileContents) {
    val state = rememberInspectorState(Inspector(journal.events, mapOf(storeId to recording)), InspectorMode.Replay)
    val controls = remember { ReplayControls(storeId, ReplaySession(machine, recording)) }
    InspectorScreen(state, replay = controls)
}

@Composable
fun ReplayGroup(recording: GroupRecording, machines: Map<StoreInstanceId, Machine<*, *, *, *>>, journal: JournalFileContents) {
    val state = rememberInspectorState(Inspector(journal.events, recording.members), InspectorMode.Replay)
    val controls = remember(recording, machines) { GroupReplayControls(GroupReplaySession(machines, recording)) }
    InspectorScreen(state, groupReplay = controls)
}

@Composable
fun ExperimentWithGroup(recording: GroupRecording, machines: Map<StoreInstanceId, Machine<*, *, *, *>>, journal: JournalFileContents) {
    val state = rememberInspectorState(Inspector(journal.events, recording.members))
    val controls = remember(recording, machines) {
        GroupTimeTravelControls(
            GroupReplayControls(GroupReplaySession(machines, recording)),
            routes = routes, // application-supplied GroupBranch.Route mappings; empty means no local delivery
            inputs = listOf(BranchInput.Dispatch(storeId, "Load", Act.Load)),
        )
    }
    InspectorScreen(state, timeTravel = controls) // mode comes from the workflow
}

@Composable
fun BranchOf(session: GroupReplaySession, machines: Map<StoreInstanceId, Machine<*, *, *, *>>) {
    val branch = remember { BranchControls(session.branch(routes), machines, listOf(BranchInput.Dispatch(storeId, "Load", Act.Load))) }
    InspectorScreen(state, branch = branch)                       // mode Branch: the panel decides what the buttons send
}
```

Group replay requires a recording and a matching machine definition/version for each member.
Its cursor counts inputs in the group recording, independently of the diagnostic journal:
partial journals and Store filters do not change those positions. Seeking and going back
restore recorded checkpoints without verifying skipped inputs. Forward re-decides the next
input and checks bridge/source causality; Verify checks the whole group without moving the
cursor. Timers, command queues and pending effects are displayed as recorded state; replay
does not start command handlers, timers or network requests.

Experiments begin from recorded executor checkpoints, so awaiting commands can be answered
and recorded timers can fire when the virtual clock advances. Application-supplied routes
map branch effects to local member actions; recorded bridge metadata cannot reconstruct
those functions. External sources use scripted `BranchInput.Feed` data. While the workflow
screen owns the cursor, do not move its replay session externally. Returning leaves all
recorded checkpoints untouched; starting another experiment creates a fresh branch.

The replay position also contains `StateChartPanel`: an interactive diagram with active and
previous nodes, selectable transitions, timer details and actual guard outcomes. The explicit
“Explain next input / check invariants” button computes one pure decision and checks the selected
snapshot; composition itself never executes guards or business IO. The panel renders metadata,
with context/action payloads left to an application's explicit projection.


`ResizableHistoryLayout` places the inspector on the right of a wide window or below the app
on a narrow window. Drag its separator to adjust width or height. Retain a
`HistoryPanelLayoutState` in the host to preserve the size when hiding the panel or navigating.
The separator also exposes an accessible range action.


Selecting entries or switching History/Live does not remove history. After the continuation
callback succeeds, the panel removes the abandoned future, keeping the selected event,
earlier entries and any new events emitted during restoration. Event IDs are never reused.
Technical diagnostics remain in the recording model but are not displayed by HistoryPanel.

### Graph diagnostics

The graph automatically inspects `definition.diagnostics`. Clean graphs show no banner.
Errors use the error palette; warnings use a separate tinted banner. Expand the banner to
read localized diagnostics, then select a problem to focus its state or exact transition.
The viewport pans and increases scale when needed. If an endpoint is missing, the existing
source/target is focused; an entirely missing node cannot be drawn.

Weather's Settings offers **Graph diagnostic demo** while the debug panel is enabled.
Cycle through Off / Warnings / Errors. It adds unreachable/no-exit states and a shadowed
transition to an inspector-only copy; Errors also adds a missing endpoint. This never
modifies the executing machine, history checkpoints, HTTP requests or saved state.

### Transition coverage

Supply `HistoryGraphCoverage.Recorded(hits)` to `HistoryGraph.Machine.coverage` to show
executed/declared transition counts. Keys are `TransitionId` indices in that exact graph
version; absent keys mean zero executions. Omit coverage to retain the ordinary graph.
Executed arrows are solid and accented, unexecuted arrows dashed. Selecting an arrow still
highlights it separately and its card shows the exact execution count, including self-loops.
This is observed coverage, not proof that unexecuted declarations are unreachable.

Production code can accumulate immutable `TransitionCoverage` from committed decisions
without a test dependency or re-evaluating guards. Record each decision exactly once; do not
register the same accumulator for both explanation and commit callbacks. Keep it scoped to
the definition/version. Test scenarios already return `MachineCoverage`; use
`HistoryGraphCoverage.Recorded(report.transitions.covered.associateWith { 1L })` to display
binary test coverage (the report does not retain execution frequencies).

Weather stores cumulative counts in each history frame, grouped by stable machine identity.
The graph shows counts up to the selected event, including earlier events evicted from the
ring buffer. Continuing from history branches from those counts; discarded future decisions
are excluded. Counts are session-only diagnostic data and reset on process restoration.

### Imported recordings

`HistoryRecordingPanel(recording, state, graph = ..., actions = ...)` is a separate inspection
surface. It provides event selection, differences, graph and filtering, with no live switch,
continuation callback, truncation or effect execution. The header explains why imported files
cannot continue execution. Render `state.selection.single().snapshot` using your stateless
screen composables, with interactions disabled. Do not instantiate screen components or stores
from an imported snapshot. Keep the existing application's state and inspector separate and
return to them when the viewer closes.

Both panel variants accept an `actions` slot on the right of the machine filter. Weather places
Import and Export with directional icons there, plus Back to session while inspecting a file.
File dialogs remain in the host, outside the library. Weather uses Android's Storage Access
Framework, Desktop's native file dialog and the browser's file input/download mechanism.

## Profiling on the graph

Supply a frozen `HistoryGraphProfile.Recorded(MachineProfileSnapshot)` on each
`HistoryGraph.Machine` to enable **Profile**. Edges use relative average input-processing
cost; selecting an edge or state displays separate queue, processing, residence and command
measurements. Missing samples display an em dash. Keep these snapshots with their history
frames so navigating the past cannot display future measurements. Imported recordings that
omit profiles use `HistoryGraphProfile.Unavailable`.

See [profiling semantics and the Weather integration](../doc/guides/temporal-schedules-and-profiling.md#4-profile-the-running-machine).
