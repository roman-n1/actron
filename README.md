# Actron

![Actron: a tangled network becomes an ordered graph, with an orange path connecting actions and states.](doc/assets/actron-chaos-to-order.png)

**From chaos to deterministic behavior.**

**MVI with an explicit model of what can happen next.**

Actron is a Kotlin Multiplatform library for features whose behavior includes requests,
cancellation, retries, timers, and independent processes running at the same time.

The recommended screen API is **a typed state machine + `MachineStore`**. Send an action from
any thread. The runtime queues inputs, makes one pure decision at a time, commits the next
state in one place, and executes commands afterward. Command handlers call your use cases
and return actions; they never write state. Compose observes state on the UI dispatcher.

## What does Actron add to MVI?

MVI gives a feature a one-way flow: **action → state → UI**. Actron also describes the allowed
behavior as a graph that the executor, diagrams, tests, and recordings share.

| Feature problem | What you declare | What Actron handles |
|---|---|---|
| A request must stop when its screen phase ends | A command in `Loading.onEnter` | Activation-scoped cancellation and rejection of stale responses |
| Typing should debounce; loading should time out | `after<Target>(duration)` inside the owning state | Timer identity, cancellation on exit, and explicit time during replay |
| Current weather and forecast load independently | Typed parallel regions | Separate lifetimes with one ordered stream of state decisions |
| Several phases share behavior | Nested states, parent transitions, history and final states | Transition selection, exit/entry ordering, and composite completion |
| The UI and debug graph disagree about the phase | One sealed state value bound with `typedStateMachine` | Derived graph IDs and invariant checks; no handwritten `GuardKey`, `EffectKey`, or action casts |
| A bug depends on a particular sequence of responses | Record machine inputs, decisions and checkpoints | Bounded recording, replay verification, and inspection of Before / Action / After |
| A parent owns a child workflow | Typed `invoke` + `InvokedMachineStore` | One atomic snapshot, scoped command/timer execution and restoration of both sides |
| A pending operation must not be blindly repeated | A command recovery strategy | Restart, reconciliation or cancellable waiting for an application decision |
| You want to continue from a past point | Restore a compatible executor checkpoint | Restartable commands and remaining timer delays; the host restores navigation |

This is a modeling choice, not a claim that other MVI libraries lack debugging or safety.
[MVIKotlin](https://github.com/arkivanov/MVIKotlin) emphasizes a single source of state and
includes logging and Time Travel. [Orbit](https://github.com/orbit-mvi/orbit-mvi) emphasizes
concise coroutine-based intents, reductions, and ViewModel integration. Actron's focus is
**an executable statechart with pure decisions and explicit command/timer lifetimes**.
Use it when those lifetimes and transitions deserve to be visible in the feature definition.
A small form or counter may not need a statechart.

Actron does not provide navigation, repositories, or dependency injection. The weather example
uses Decompose for component lifetimes and navigation. `actron-core` remains a flexible,
lower-level Store API; arbitrary core handlers do not have the pure Machine contract.
Library and example sources follow a CI-enforced [null-free policy](doc/guides/absence-policy.md),
including compiler-resolved types; absence is modeled as explicit domain states.

[Typed MVI quick start](#1-a-store-for-a-counter) · [Threading and commands](doc/guides/machine-mvi.md) ·
[Weather demo](examples/weather/README.md) · [Installation](#installation) ·
[Null-free API policy](doc/guides/absence-policy.md)

### Determinism you can verify

On the pure Machine path, the same starting snapshot and ordered inputs — including explicit
time — produce the same decisions when the machine definition and pure rules are unchanged.
Network responses enter as inputs; commands leave as data. Replay checks recorded decisions
without running the network calls again.

That gives a bug report a concrete next step: **open the recording, find the decision, try an
alternative.** Deterministic replay requires the pure Machine path and compatible definitions
and codecs. Replay, branching and inspector APIs are currently experimental.

### Start small. Make complexity explicit.

A counter can use a single state. A loading screen needs clear phases. A feature with nested or
parallel lifetimes can use a statechart. Add recording and Time Travel when you need them;
choose the modules that fit your feature.

The name carries the idea: **Act** for the actions that drive behavior, **-tron** for the
engine that gives them structure.

## Start here

| You want to… | Start with… |
|---|---|
| Understand typed actions, state and a Store | [The counter below](#1-a-store-for-a-counter), then [the MVI contract](doc/guides/machine-mvi.md) |
| Model Loading → Content or Error | [Typed states](actron-statechart/README.md#state-types-shared-by-graph-ui-and-recordings) |
| Describe nested/parallel phases and draw their transitions | [actron-statechart](actron-statechart/README.md) |
| Keep I/O separate from decisions and replay them | [A pure Machine below](#3-a-machine-makes-decisions-io-runs-afterward) |
| Connect a Store to Compose | [actron-compose](actron-compose/README.md) |
| Reproduce a bug from a saved run | [The runnable Time Travel example](examples/time-travel/README.md) |
| Explore MVI, a live API and an embeddable debug panel | [The Multiplatform weather demo](examples/weather/README.md) |
| Reproduce cancellation, failure and recovery for a Time Travel walkthrough | [The scripted article scenario](examples/weather/README.md#a-repeatable-story-for-an-article) |
| Handle retained effects across process death | [The durable outbox example](examples/durable-effects/README.md) |
| Look up the full original Store DSL | [Store API guide](doc/guides/store-api.md) |

## 1. A Store for a counter

The typed DSL declares transitions and handlers inside their state. Here one state owns
context-only updates; no separate node IDs or reducer registration strings are needed.

```kotlin
import actron.core.Action
import actron.statechart.machine.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

data class CounterState(val count: Int = 0)
sealed interface CounterAction : Action {
    data object Increment : CounterAction
    data object Decrement : CounterAction
}

val counterMachine = typedStateMachine<CounterState, CounterAction, Nothing, Nothing>(
    DefinitionId("counter"), DefinitionVersion("1"), CounterState(),
) {
    state<CounterState> {
        internal<CounterAction.Increment> { context, _ -> context.copy(count = context.count + 1) }
        internal<CounterAction.Decrement> { context, _ -> context.copy(count = (context.count - 1).coerceAtLeast(0)) }
    }
}

fun counterStore(scope: CoroutineScope) = MachineStore(
    machine = counterMachine,
    context = CounterState(),
    handler = CommandHandler<Nothing, CounterAction> { _, _ -> },
    scope = scope,
    coroutineContext = Dispatchers.Main, // Choose where decisions and commits run.
)
```

Create the Store once for its owner's lifetime. `store.dispatch(CounterAction.Increment)`
queues an action and starts a lazy store; it does not wait for the new state. Collect
`store.state` and render `snapshot.context.count`. Call `store.close()` when the owner is
destroyed. `Nothing` means this machine has no commands or events.

For Compose, `val snapshot by store.state.collectAsUiState()` from `actron-compose` collects
on Main even if the machine processes on a background dispatcher. The platform must provide
`Dispatchers.Main` and create its composition on the UI thread. This is independent of
`scope`, which supplies the command execution context and lifetime.

The queue preserves acceptance order per machine. A single-thread dispatcher or Main also
pins processing to one physical thread; Default/IO pools still process inputs sequentially,
but may use different threads. See the [full threading contract](doc/guides/machine-mvi.md).

For the original, more permissive coroutine Store DSL, see [actron-core](actron-core/README.md).

## 2. Which kind of state machine do you need?

A **state machine** says which transitions are allowed. A **statechart** extends that idea
with hierarchy, parallel regions and history. Choose the smallest model that explains your
feature's behaviour.

| Shape | Example | Actron representation |
|---|---|---|
| One state with changing data | A counter or an editable form | A data class and `internal<Action>` updates |
| Flat, mutually exclusive phases | Idle → Loading → Content or Error | Sealed values with `typedStateMachine` |
| Hierarchical phases | Connected contains Idle and ChatOpen | `compound<Parent>`; one active child at a time |
| Parallel phases | Connection status and upload status evolve independently | `parallelStateMachine` regions, or nested `parallel<Parent>` |
| Return to a previous substate | Resume an interrupted multi-step editor | `history()` / `history(deep = true)` and `onHistory<Action, Parent>()` |
| Timed transitions | Debounce a query or leave Loading after a timeout | `after<Target>(duration)` inside a state |

For example, ChatOpen and Idle share the Connected lifetime:

```mermaid
stateDiagram-v2
    [*] --> Disconnected
    Disconnected --> Connected: Connect
    state Connected {
        [*] --> Idle
        Idle --> ChatOpen: OpenChat
        ChatOpen --> Idle: CloseChat
    }
    Connected --> Disconnected: Disconnect
```

Moving Idle → ChatOpen keeps Connected active. Disconnect exits the active child and then
Connected. In parallel regions, Connection.Online and Upload.Sending can both be active; they
are not competing choices in one sealed state.

**Keep data as data.** A query string, selected contacts, a counter and scroll position belong
in state/context fields. Loading, Editing and WaitingForConfirmation are useful nodes because
they change which actions, commands or lifetimes are allowed. You do not need a node for every
possible query or count.

Internally, `StateId` identifies a node and `StateConfiguration` tracks active nodes. The typed
DSL derives these graph identities from your state types and binds them to the values the UI
reads. Shared data can live in a separate context; when the typed state is the complete model,
the short overload supplies the binding. The runtime exposes both graph and data in a snapshot.

## 3. A Machine makes decisions; I/O runs afterward

There are two ways to execute a chart:

| Runtime | Use it when… | State exposed by the Store |
|---|---|---|
| `StateChartStore` | You want chart lifetimes with coroutine activities and hooks | `ChartState<C>` |
| `Machine` + `MachineStore` | You want pure decisions, explicit commands and replay | `MachineSnapshot<C>` |

A pure `Machine` takes a snapshot and an input and returns a `Decision`. Commands, timer
changes and effects are data in that decision. `MachineStore` commits it, then a
`CommandHandler` performs I/O and sends results back as new inputs.

The counter above can also run without coroutines:

```kotlin
val started = counterMachine.decide(
    counterMachine.initialSnapshot(CounterState()), MachineInput.Start(MachineTime.Zero),
)
val incremented = counterMachine.decide(
    started.snapshot, MachineInput.Dispatch(CounterAction.Increment, MachineTime.Zero),
)
// incremented.snapshot.context.count == 1; no Store or I/O was started.
```

For a request, entering Loading registers a command. A `commandHandler(onFailure = ...)`
executes the use case and returns a success or failure action. `commandHandlers { handle<T> { ... } }`
routes several command types without casts or manual result emission. The runtime owns the
queue, cancellation and dispatch of those results. See the [request example](doc/guides/machine-mvi.md#request-handlers).

The typed DSL supports [sealed phases](actron-statechart/README.md#state-types-shared-by-graph-ui-and-recordings),
[parallel regions](actron-statechart/README.md#typed-parallel-regions),
[hierarchy, history and completion](actron-statechart/README.md#typed-hierarchy-history-and-completion),
and [child invocation](actron-statechart/README.md#state-local-typed-invocation).
Explicit-ID definitions remain available for lower-level tools and existing users.

## 4. Time Travel from a real saved run

The [desktop example](examples/time-travel/README.md) has a cart and an order summary. A bug
forgets discount 20 on price 100, so both machines show 100 instead of 80.

```sh
./gradlew :time-travel-example:run
# The same full workflow without opening a window:
./gradlew :time-travel-example:run --args='--check'
```

Record checkout → reopen the saved files → compare the corrected reducer → see the first
divergence → branch from its checkpoint → script a price response → return to the original
replay. Group navigation restores every member at one position. Experiments use a virtual
clock and application-supplied inputs/routes; they leave the recording and live runtime alone.

An arbitrary coroutine Store can be inspected, but deterministic replay needs the pure
Machine path and compatible definitions/codecs. Debug tooling belongs in debug dependencies;
the CI checks that production modules do not depend on it.

## Skills for your AI

Reusable agent instructions live in [skills/](skills/README.md): modeling with the typed DSL,
virtual-time testing and coverage, and extracting graph/recording diagnostics. The skills
include module routing and check the current API version before proposing code.

## Modules

Every library module has its own README with setup, examples and its contract.

| Module | Purpose |
|---|---|
| [actron-core](actron-core/README.md) | Store DSL, immutable state, actions/events, coroutine lifetimes and persistence |
| [actron-compose](actron-compose/README.md) | `ViewStore`, state rendering, derived UI values and transient event collection |
| [actron-message](actron-message/README.md) | Process-wide message plugin for ordinary Stores |
| [actron-logging](actron-logging/README.md) | Store logging and structured journal output |
| [actron-test](actron-test/README.md) | Await processing, record states/events and diagnose handlers |
| [actron-statechart](actron-statechart/README.md) | Declarative model, validation, diagrams, chart runtime and pure Machines |
| [actron-statechart-compose](actron-statechart-compose/README.md) | Retained Machine effects delivered to Compose |
| [actron-statechart-test](actron-statechart-test/README.md) | Scenario DSL, scripted commands, virtual time and transition coverage |
| [actron-diagnostics](actron-diagnostics/README.md) | KMP causal traces for decisions, commands, timers and bridge effects |
| [actron-diagnostics-sdk](actron-diagnostics-sdk/README.md) | Optional OpenTelemetry and Sentry adapters for JVM/Android |
| [actron-diagnostics-crashlytics](actron-diagnostics-crashlytics/README.md) | Optional Android Crashlytics adapter |
| [actron-observability](actron-observability/README.md) | Ordered, bounded diagnostic journal and file storage |
| [actron-timetravel](actron-timetravel/README.md) | Recording, codecs, checkpoints, replay and isolated branches |
| [actron-timetravel-compose](actron-timetravel-compose/README.md) | Inspector UI and group replay/experiment controls |
| [time-travel-example](examples/time-travel/README.md) | Runnable JVM debug application; not a published library |
| [weather-example](examples/weather/README.md) | Compose Multiplatform MVI weather app with a live API and resizable debug panel; not a published library |
| [durable-effects-example](examples/durable-effects/README.md) | Runnable JVM outbox and idempotent-effect application; not a published library |

## Installation

Actron's configured coordinates are **`io.github.roman-n1:<module>:5.0.0-alpha.1`**.
GitHub releases provide source archives and may include an IDE plugin ZIP. Maven Central
publication is separate; for development, use a local checkout/composite build:

```kotlin
// settings.gradle.kts in your application
includeBuild("../actron") // path to this repository; its root name is Actron
```

```kotlin
// Kotlin Multiplatform build.gradle.kts in your application
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.roman-n1:actron-core:5.0.0-alpha.1")
            // Optional: implementation("io.github.roman-n1:actron-statechart:5.0.0-alpha.1")
        }
    }
}
```

The publishing convention sets the included projects' group/version, so Gradle substitutes
the matching coordinates. Add only the modules you need. External sources and debug
tools currently need `@OptIn(actron.core.ExperimentalActronApi::class)` at their use sites.
Keep all Actron modules on the same version. Actron uses `actron.*` packages and requires
recompiling consumers that previously imported `koma.*`; it is not a binary replacement.
For an Android-only build, the same dependency line belongs in `dependencies { … }`.

## What differs from the original project?

Actron was previously named Koma in this fork, based on upstream
[koma-kt/koma](https://github.com/koma-kt/koma) **4.0.0**. The source repository is currently
[roman-n1/actron](https://github.com/roman-n1/actron). It adds an optional declarative statechart
model, pure decision machines, structured recording and Time Travel.
The original Store DSL remains available under the `actron.*` packages.
See [Migrating from Koma](doc/guides/migrating-from-koma.md) for the breaking rename.

Actron evolves independently. Source, binary and runtime interoperability with upstream
Koma 4.0.0 are unsupported; consumers must migrate to Actron and recompile. Compatibility
with Koma does not constrain Actron's API or releases.

The comparison is with the recorded **upstream base 4.0.0**, not a claim about future upstream
releases. The original Store DSL, Compose helpers, messaging, logging and test support remain.

| Area | Upstream base 4.0.0 | This fork adds |
|---|---|---|
| State management | State-specific Kotlin Store handlers | Optional statechart nodes/transitions as inspectable data |
| State structure | Ordinary Store states | Hierarchy, parallel regions, history and chart timers |
| I/O and decisions | Coroutine work in Store handlers | Pure Machine decisions and explicit command/executor checkpoints |
| Diagnostics | Plugin observation and logging | Ordered structured journal, bounded retention and file readers |
| Debugging | Inspect/test Store behaviour | Single/group replay, divergence details and isolated experiments |
| UI/test integration | Core Compose and test helpers | Retained effect mailbox, virtual Machine test driver and inspector |
| Runtime/build assurance | Original runtime and build | Documented race/error fixes, API dumps, debug dependency checks and six CI targets |

The pure chart/Machine data model, journal and recording formats have a stable API/format
policy. Evolving Store-adapter configuration, external-source integration, replay and inspector
APIs retain experimental markers; see the [stability boundary](doc/internal/adr/2026-10-01-stable-core.md).
The supported subset includes eventless/completion transitions, final states, internal
transitions and cancellable invocation. Device measurements and messenger integration
remain separate work. The [divergence inventory](doc/internal/design/2026-09-28-statechart-roadmap.md#divergence-inventory-vs-upstream-400)
records the individual changes and their route back upstream.

## Contributing and deeper reading

- [Kotlin code style](doc/guides/code-style.md): `./gradlew ktlintCheck` to verify, `./scripts/format-kotlin.sh` to format the entire repository.

- [Store API guide](doc/guides/store-api.md): recovery, launch/transaction, persistence, policies and full plugin examples.
- [Absence policy](doc/guides/absence-policy.md): migration away from null and optional containers, API changes and the CI guard.
- [Device resource budgets](verification/resource-soak/README.md): required Android/iOS soak runs, resource cleanup, memory plateau and commit latency reports.
- [Internal documentation](doc/internal/README.md): architecture, decisions, review evidence and roadmap.
- [IntelliJ plugin](tooling/intellij-plugin/README.md): K2 gutter, model Find Usages and diagram navigation.
- [Build conventions](build-logic/README.md): coordinates, publishing and project setup.
- Validation: `./gradlew jvmTest apiCheck checkDebugGraph`; the CI also tests Android, iOS, JS and Wasm.
- Published artifacts: `scripts/verify-published-consumer.sh jvm` builds an
  [independent Maven consumer](verification/published-consumer/README.md); CI also compiles its
  Android, JS, Wasm and iOS variants.

Published-consumer checks reject upstream Koma dependencies in Actron's dependency graphs.
