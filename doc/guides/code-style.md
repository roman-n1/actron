# Kotlin code style

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
