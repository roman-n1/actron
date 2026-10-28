# Kotlin code style

Use explicit parameter names for arguments in new or edited Kotlin calls, including
constructors and DSL callbacks (`build = { ... }`, `to = { ... }`). Calls without arguments
need no extra syntax. Keep positional syntax only where Kotlin forbids named arguments,
such as Java methods, function-type invocation, operators and vararg elements.

All authored `.kt` and `.kts` files use the root `.editorconfig`, including library modules,
examples, tests, Gradle scripts, included builds and standalone tooling/verification builds.
Build outputs, IDE caches and downloaded dependencies are excluded.

```sh
# Verify without changing files (also run by CI and Gradle check tasks)
./gradlew ktlintCheck

# Reformat the entire repository
./gradlew ktlintFormat

# Equivalent wrapper, callable from any working directory
./scripts/format-kotlin.sh
```

The formatter is ktlint, pinned in `gradle/libs.versions.toml`; it is downloaded through Gradle,
so a global installation is not needed. The integration is in `gradle/ktlint.gradle.kts` and
follows the [ktlint CLI integration](https://ktlint.github.io/ktlint/1.8.0/install/integrations/).
The wrapper script forwards extra arguments to Gradle, for example `--offline` once dependencies
are cached. Formatting is explicit; ordinary compilation does not modify source files.

The shared style uses four spaces, LF, a final newline, trailing commas and ktlint's official
formatting rules. `@Composable` functions may start with uppercase letters. Keep EditorConfig
support enabled in Android Studio / IntelliJ so editor formatting uses the same settings.

Ktlint checks Kotlin only. It does not format Java, Markdown, YAML, shell, serialized fixtures
or API dump files. The strict null-safety checker remains a separate required verification.

Naming and filename rules are disabled so style cleanup does not rename public DSL APIs or JVM
file facades. Wildcard imports are accepted, while IDE auto-import stays configured for explicit
imports. These are repository-wide choices, not per-file suppressions or a debt baseline.

## Complexity and code smells

Detekt is pinned to 2.0.0-alpha.6, matching `kmp_messenger_2` (the source of this quality setup).
`config/detekt/detekt.yml` carries its default rules and explicit bug checks, without the
messenger's incident history and internal task references. There is no formatting ruleset:
ktlint owns formatting. Existing library API naming exceptions in `.editorconfig` are retained.

```sh
./gradlew detekt                      # all main-build modules
./gradlew :actron-core:detekt          # one module, every Kotlin source set
```

`check` depends on analysis, and CI runs `ktlintCheck detekt`. Reports are written to each
module's `build/reports/detekt/detekt.sarif`. Analysis is plain CLI: it checks common and
platform-specific sources without compiling targets. Rules requiring type resolution need a
future full-analysis lane; they are not claimed as covered by this task. Included builds,
standalone verification/tooling builds and Gradle scripts are covered by ktlint, but are outside
this first Detekt lane.

Default thresholds are inherited from the pinned engine, including LongMethod (60 lines),
LargeClass (600 lines), CyclomaticComplexMethod (14), CognitiveComplexMethod (15) and
TooManyFunctions (11 per file/class). LongParameterList also remains enabled. These are review
signals, not a reason to split an atomic operation or scatter related facts. Test source sets
are exempt from size counters, as in the messenger; complexity and correctness checks remain.
Composable length/parameter exemptions match the source project. File-level function counts
and class size are checked; there is no separate physical-file line limit in this lane.

Exact initial debt lives in `config/detekt/baselines/<module>.xml`. This onboarding intentionally
retains existing findings, including the remaining StoreImpl function count and generic exception catches
at framework boundaries. Two catch findings move with their existing code to StoreInputs and
StorePlugins; their broad failure handling preserves cancellation/fatal-error semantics.
New helpers have no size/complexity exemptions. Baselines may only shrink after onboarding;
`detektCreateBaseline` exists for initial inventory, never to approve a new violation. A new
module should start clean. A passing baseline-backed check does not mean the old debt is gone.

The rules and workflow are linked from `AGENTS.md`; architectural decisions use the local
`skills/high-cohesion-low-coupling` and `skills/static-analysis` skills.

`checkDetektBaselines` rejects new baseline entries relative to HEAD, or to
`-Pactron.quality.base=<revision>`. CI supplies the PR base (or previous push revision).
Only the first onboarding, where that revision has no Detekt configuration, may establish
initial debt. Both current and manually suppressed entries are checked; four contract tests
cover the ratchet in `scripts/test_detekt_baselines.py`.
