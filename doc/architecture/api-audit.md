# MVI API audit

## Scope and evidence

This audit covers the existing MachineStore, screen DSL, Decompose retention, Compose history and
Weather. The working checkout already contained a Weather simplification before this change:
application checkpoint/session coordinators had been removed and live history was view-only.
The comparison below uses Git HEAD for reproducibility; it includes that earlier simplification
where noted, and does not attribute all checkout changes to this audit.

| Existing boundary | Finding | Decision |
|---|---|---|
| `actron-statechart/.../machine/MachineStore.kt` and `StateMachineDsl.kt` | Machine decisions already own state; no competing reducer is needed | Preserve production decision path |
| `ScreenMachine.kt` | Entry coroutine already returns typed actions; compact async declaration exists | Keep `screenMachine`, `on`, `onEnter`; do not add another feature DSL |
| Weather component intent handlers | Selection/back and child commands could bypass the machine via presentation commands | Map UI payload to domain action; emit committed domain event |
| Weather custom UiStateFlow classes | Repeated projection implementations | Provide `present`, `projectState`, `combineStates` in statechart |
| Decompose retention | Retaining a store does not reconstruct a historical tree | Optional session module owns replacement and persistence |
| History panel | Synchronous continuation cannot safely wait for store shutdown | Add suspend `ResumableHistoryPanel`; truncate only on success |
| Core / adapters | Core has no Compose or Decompose dependency | Preserve dependency direction |

## Alternatives and trade-offs

1. Another generic MVI engine/reducer interface would duplicate the machine's authority and graph.
   Rejected. Actron is the MVI runtime; the state machine is its reducer.
2. A new `actron { execute; reduce }` DSL would compete with the existing screen DSL. Rejected:
   `onEnter` already handles the simple case without Command/Executor classes.
3. Restoration in the base Decompose module would impose serialization on retention-only users.
   Chosen: `actron-decompose-session` depends on Decompose and persistence. It hides its controller
   and navigation abstraction; applications supply ordinary component factories and serializers.
4. Automatically infer domain/UI conversion is unsafe: UI identifiers and business identity are
   application semantics. Chosen: two explicit pure mapper functions with a reusable presenter.

The execution responsibility is called **Executor** in the architecture. Existing public
`CommandHandler` remains the explicit command executor contract; renaming it would break users
without changing behavior. Inline `onEnter` is the canonical simple path. No parallel public
`Actor`/`EffectHandler` synonym is introduced.

## Measured before → after

Method: physical lines including imports/blank lines, Git HEAD versus working tree, limited to
both Weather components, both mappers and their custom UiStateFlow files. This is a reproducible
presentation-boundary sample, not a claim that all feature code or all boilerplate shrank.

| File | HEAD | Updated |
|---|---:|---:|
| CitySearchComponent | 68 | 74 |
| CitySearchMapper | 63 | 62 |
| CitySearchUiStateFlow | 23 | 0 |
| CityWeatherComponent | 66 | 70 |
| WeatherStateMapper | 180 | 160 |
| CityWeatherUiStateFlow | 26 | 0 |
| Total | 426 | 366 |

Six files become four. Two custom flow types and two UI-command hierarchies are removed; committed
CitySearchEvent and additional Weather action/event variants make previously bypassed paths explicit.
Type count is therefore not the sole success metric: domain outputs now participate in diagnostics.
For one simple screen: state + domain action + UI state/event + two mapper functions; an output type
only when external notification is needed. No mandatory Result, Executor class, StoreFactory or ViewModel.

Configuration for that screen: one machine declaration, one `sessionMachine` installation, one
`present` boundary. Navigation restoration adds one root assembly and a definition list per app;
Weather's composed four-machine group also needs group tracking and deferred startup hooks.
Adding a synchronous intent changes the UI event, its mapper, domain action and DSL handler.
Adding async work adds a loading state/entry, typed result actions and their guarded transitions;
no separate result hierarchy is required. Both paths use the existing recorder/graph/coverage.

## Limits

Session integration is optional, explicit and not zero configuration. Stable instance keys,
serializable domain payloads, navigation serializers and a repeatable root factory are required.
Full durable process restoration and remote-write recovery are application policies. Session factory
failure is observable but not a transactional rollback of the previous running tree. Coverage,
recording size and serialized payload cost remain relevant for large messenger trees.

[Executed checks and behavioral evidence](verification.md) record the exact commands and limits.
