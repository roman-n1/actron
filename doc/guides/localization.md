# Native Compose Multiplatform localization

UI copy is owned by each presentation module in `src/commonMain/composeResources`:

- `actron-timetravel-compose`: reusable Inspector, history, replay, branches and graph.
- `examples/weather/shared`: Weather feature screens.
- `examples/weather/debugTools`: Weather debug settings and recording file controls.

`values/strings.xml` contains default English text; `values-ru/strings.xml` contains Russian.
Use generated `Res.string.*` references and native `stringResource(resource = ..., ...)`.
Presentation mappers retain resource identifiers and arguments; translation is resolved at the
Compose rendering boundary, so a saved snapshot is not bound to the language in which it was made.
Technical identifiers, recorded payloads, journal text and exception details remain recorded data.

To add a language, copy the resource keys into `values-zh/strings.xml`, `values-ar/strings.xml`
or `values-ja/strings.xml` and translate the values. No Kotlin language branches or custom
catalog generator are needed. Keep numbered placeholders (`%1$s`, `%2$s`) and reorder them
in the translation where needed. Compose handles locale selection, English fallback and system
RTL layout. Android applications must enable `android:supportsRtl="true"`; web hosts adding
scripts beyond their installed fonts must also provide/preload suitable fallback fonts.

Native resource docs: https://kotlinlang.org/docs/multiplatform/compose-localize-strings.html
RTL/platform details: https://kotlinlang.org/docs/multiplatform/compose-rtl.html

JVM integration tests render the same Weather snapshot in Russian, English and an unsupported
locale, and verify the Inspector in Russian, English and fallback. Core model tests remain pure.
