---
name: compose-presentation
description: "Compose Actron: готовые решения из mapper/controls, чистая отрисовка, локальное UI-состояние и запрет булевых операторов."
---

# Compose рисует готовое решение

Адаптировано по запросу владельца из правил KMP Messenger; его DS, инфраструктура,
ресурсы и внутренние реализации сюда не переносятся.

- Единственный владелец факта — существующий state/controls. Не дублируй его mutable-поля
  в промежуточной модели экрана. Mapper строит временный immutable presentation snapshot.
- Решения о доступности, принадлежности, фильтрах, сортировке и форматировании — в mapper
  или владельце состояния. Composable размещает готовые части и передаёт намерения владельцу.
- Внутри `@Composable` (также callback/default/typed lambda/getter) и пакетов `uimodel`
  запрещены `&&`, `||`, `!`, `!in`, `!is`, вызовы/ссылки `not/and/or/xor`, включая infix.
  `!=` не входит в этот запрет; `!!` отдельно запрещён политикой отсутствия.
- Обычный `if`/`when` для готового решения, презентационный тип → ресурс/цвет и геометрия
  разрешены. Не маскируй бизнес-правило через `== false`, вложенные if или helper в разметке.
- Локальные раскрытие/меню/жест принадлежат UI. Для переключения используется
  `MutableState<Boolean>.toggle()` вне Composable; не переноси этот transient state в Store.
- UiModel — данные, без `fun`, `var`, вычисляемых getters и ветвления. Фабрики — в mapper.
  Не добавляй Boolean в публичный UiModel API и не заменяй его пустым двухсостоянийным
  wrapper или nullable скрытым флагом. Существующие публичные сигнатуры сохраняются для ABI.
- Сохраняй размеры/weights, lifetime remember/key, порядок callbacks, test tags и семантику
  accessibility. Отдельные UI-тесты проверяют реальные сценарии через публичные composables.

Проверка — `./gradlew checkComposeNoBooleanLogic`; она включена в `detekt` и CI.
Kotlin PSI отделяет исполняемые выражения от комментариев/строк. Тестовые source sets не
входят в периметр, как в KMP. Импортированные Desktop entry points также проверяются.
Это syntax guard, не доказательство, что все доменные правила уже вынесены из UI.

`config/compose/boolean-logic-baseline.tsv` — первичный точный оставшийся долг существующих
файлов. Новые, заменённые и повторно добавленные выражения запрещены. Устаревшие записи
также блокируют проверку. `:nullability-guard:pruneComposeBooleanBaseline` только сокращает
baseline; относительно `-Pactron.quality.base=<ref>` добавления запрещены. Не переписывай
baseline из report и не добавляй suppress/exclude ради GREEN.

API dumps содержат служебные `ComposableSingletons` (JVM) и `$stableprop` (klib),
генерируемые Compose. При переносе тел проверь diff отдельно: authored Kotlin/JVM/klib
API должны остаться прежними; обновление только compiler-generated записей явно
отмечается в результате и PR.
