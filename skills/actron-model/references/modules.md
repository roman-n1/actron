# Module routing

Use the root README's Modules table and each `<module>/README.md` as the API authority.
Read only the modules relevant to the task. This map explains boundaries, not every symbol.

| Need | Module / boundary |
|---|---|
| Ordinary Store, actions/events, lifecycle, immutable state | `actron-core` |
| Compose rendering of ordinary stores | `actron-compose` |
| Process-wide messages between ordinary Stores | `actron-message`; not a substitute for typed machine commands |
| Logging a Store | `actron-logging` |
| Test ordinary Stores | `actron-test`; machine scenarios belong in the module below |
| Typed DSL, hierarchy, parallel states, invoke, executor | `actron-statechart` |
| Deliver retained machine effects in Compose | `actron-statechart-compose` |
| Scripted machine commands, virtual clocks, coverage, exploration | `actron-statechart-test`; test dependencies only |
| Bounded recording, persistence codecs, replay, branching, groups | `actron-timetravel` |
| History panel, graph, resizing, locale, diagnostics UI | `actron-timetravel-compose`; supplies UI, not app navigation |
| Bounded ordered diagnostic journal / storage | `actron-observability` |
| Causal traces spanning decisions, commands, timers and effects | `actron-diagnostics` |
| JVM/Android OpenTelemetry or Sentry integration | `actron-diagnostics-sdk`; optional backend adapter |
| Android Crashlytics integration | `actron-diagnostics-crashlytics`; optional Android adapter |

Examples are applications, not published dependencies:

- `examples/weather`: two-screen Compose/Decompose example, search and parallel current/
  forecast machines, session history, persistence adapter. Android launcher: `examples/weather-android`.
- `examples/time-travel`: focused replay/debug application.
- `examples/durable-effects`: durable/outbox and idempotency example.

Repository-only tooling:

- `verification/behaviour-review`: model snapshots and review artifacts.
- `verification/resource-soak`: resource-budget tests.
- `verification/nullability-guard`: strict nullability checks.
- `tooling/intellij-plugin`: IDE support; not part of the runtime.
- `build-logic`: Gradle conventions; `settings.gradle.kts` gives Gradle project names.

Use `rg --files <module>/src` to find concrete declarations and tests. Do not add a production
dependency on a test module merely to obtain a report type or convenience helper.
