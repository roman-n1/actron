---
name: actron-model
description: Design and integrate Actron state machines using its typed Kotlin DSL, choose the relevant modules, and connect execution and Compose UI. Use for Actron-based application or library changes.
---

# Build with Actron

Find the Actron checkout or the consumer's resolved Actron version first. Paths below are
relative to that checkout, not to the installed skill directory. If source is unavailable,
use documentation matching the installed artifact version; do not present branch-only APIs
as released features.

For module selection, read [references/modules.md](references/modules.md). For API shape,
start with `README.md`, `doc/guides/machine-mvi.md` and the relevant module README, then inspect
the signatures in `actron-statechart/src/commonMain/kotlin/actron/statechart/machine/`.

## Modeling decisions

- Prefer the typed DSL used by Weather's `CitySearchMachine.kt` and `CityWeatherMachine.kt`.
  A direct sealed state can be the complete context. A context-based machine binds its state
  once through read/write functions. Do not maintain a separate phase enum and StateId map.
- Use parallel regions for independent concurrent lifecycles, not a bag of loading flags.
  Use hierarchical states for shared behavior/lifetime. Consult existing hierarchy/history/
  invoke examples before introducing equivalent hand-written coordination.
- Keep reducers and guards pure. Declare commands in machine hooks and execute use cases in
  command handlers. Results return as typed actions through the Store. Choose command scopes,
  lanes and cancellation policies to match the domain; do not bypass them with detached jobs.
- A Machine describes behavior; MachineStore owns queued processing. Use the documented
  processing/UI dispatch configuration. A dispatcher pool does not imply a dedicated OS thread.
  Do not mutate published state directly or claim all side effects run on the UI thread.
- Decompose/Android navigation and saved-state integration belong in the application adapter.
  The machine library must not depend on a navigator or an Android lifecycle.
- Definition IDs, versions and typed-state names participate in persistence. Evaluate version
  compatibility when renaming states, reordering transitions or changing executable rules.

## Implement and verify

Follow the module's nearest working example rather than inventing constructors. Weather is
under `examples/weather/shared/src/commonMain/kotlin/actron/example/weather/`; its root/components
show how to connect reusable machines, feature command handlers and UI.

Use the smallest relevant tests and compile targets. New public Kotlin API requires the
repository's API dump/check workflow. Changes must pass `ktlintCheck` and `checkNullability`;
this repository deliberately rejects nullable API/state shortcuts. Run `ktlintFormat` before
`ktlintCheck`, not concurrently. Preserve unrelated working-tree edits.

Report the resulting state/action/command flow, validation actually run and any behavior
that remains unverified. A skill does not authorize pushes, merges or deployment.
