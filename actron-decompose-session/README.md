# Decompose sessions

Optional adapter for restoring machine executors and ordinary Decompose navigation together.
The base `actron-decompose` module stays independent of persistence.

A component installs its serializable machine with a stable session-wide instance key:

```kotlin
val store = componentContext.sessionMachine(
    key = "search",
    definition = CitySearchMachine.create(repository).restorable(),
    initial = CitySearchState.Idle(),
)
val presentation = store.present(
    stateMapper = { it.toUiState() },
    actionMapper = UiActionMapper<CitySearchIntent, CitySearchState, CitySearchAction> { event, state, dispatch ->
        event.toAction(state, dispatch)
    },
)
```

At the root, construct `RestorableComponent(context, identity, version, definitions) { child ->
AppRoot(child) }` and render its `component` StateFlow. Existing `childStack` / `childSlot`
serializers supply navigation; no application navigation adapter is needed. Register definitions
once, using `machine.restorable()` (generated kotlinx serializers) or `SessionMachine(machine, codec)`.
Keep instance keys unique across the tree, including multiple instances of one screen.
Outside this root, `sessionMachine` uses ordinary InstanceKeeper retention.

`checkpoint()` captures a coordinated executor cut. `recordedCheckpoint()` is for decision
recording callbacks on the owning UI dispatcher: it returns `Ready` or a reason continuation is
unavailable. Run navigation, machine decisions and capture on that dispatcher. A composed feature
registers its routed group with `trackSessionGroup` and defers grouped startup with `whenSessionReady`.
Simple single-machine screens need neither hook.

`continueFrom(checkpoint)` cancels and joins old stores, creates fresh keepers and restores the
saved tree, then starts its executors. It also replaces instances when routes are identical.
The root follows its parent's lifecycle and closes on destruction. Component factories must be
repeatable; do not capture the old component in retained machine handlers.

`encode` / `decode` support application-owned storage. The app chooses when and where to persist;
the adapter does not write files. Session identity/version and every definition are validated before
stopping live executors. Use account-specific identities where data must not cross accounts.
A factory failure during replacement is reported; it does not promise transactional recovery of the
old running tree. The history UI keeps the selected point and permits another restore attempt.

Preview and deterministic replay never run IO. Continuation deliberately resumes execution:
uncompleted commands/entries may restart. Use restartable reads; irreversible writes need an explicit
application recovery/idempotency design. Restoration does not undo remote writes or redeliver old
transient UI events. Imported Weather display recordings remain view-only; they are a different
format from encoded executable session checkpoints.

See [Weather's actual assembly](../examples/weather/debugTools/src/commonMain/kotlin/actron/example/weather/app/debug/RestorableWeatherRoot.kt)
and [migration](../doc/architecture/migration-guide.md).
