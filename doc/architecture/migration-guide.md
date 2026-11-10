# Migrating a screen

1. Keep the existing `screenMachine`: domain state/actions and guarded transitions are its single
   reducer. Use `onEnter` for simple cancellable IO; dispatch typed success/failure actions.
2. Map UI events to domain actions before dispatch. UI-only fields stay in the UI contract.
   Replace presentation command dispatchers with `store.present(stateMapper, UiActionMapper(...))`.
   Expose its read-only state and forward `onUiEvent`.
3. Move navigation decisions into domain action → `onEvent` output. Observe the committed output
   through `observeEvent(lifecycle)` and map it to the existing parent bridge contract.
4. For cross-screen continuation, replace `retainedMachine` with `sessionMachine`, supply
   `machine.restorable()` and a stable session-wide key. Wrap the ordinary root in
   `RestorableComponent` and render its current component flow. Register the machine definitions.
   Existing Decompose route serializers remain the source of navigation state.
5. For composed groups only, use `start = false`, wire routes, `trackSessionGroup`, then
   `whenSessionReady { stores.forEach { it.start() } }`.
6. Use `ResumableHistoryPanel` for suspend restoration. Existing synchronous `HistoryPanel`
   overloads remain available. Imported display recordings remain read-only.

Public changes: new MVI presenter/projection functions, screen `onEvent`, session module,
`MachineGroup.Member.release`, suspend history panel. `retainedMachine` adds `start` with default
`true`; Kotlin named call sites keep their behavior, but its binary signature changes and consumers
must recompile. Group members must close stores before release. Weather definition versions change
(city-search 16 → 17, city-weather 12 → 13) because action/output behavior changes; old incompatible
recordings are rejected rather than silently interpreted with the new definitions.

Test a new screen with pure machine decisions, a controlled repository and a real MachineStore.
Check both success and failure, cancellation, stale results, ignored actions and actual output delivery.
Use the Weather continuation tests for cross-screen replacement; preview/replay tests must assert zero IO.
