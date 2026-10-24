---
name: clean-architecture
description: "Границы модулей Actron и размещение правил библиотеки: core, statechart, адаптеры, observability и debug tooling."
---

# Границы библиотеки

Адаптация принципов KMP Messenger, без прикладных слоёв мессенджера.
Core — механизм Store, его DSL и порты. Statechart добавляет свои правила поверх core.
Compose, logging, diagnostics и persistence потребителя — адаптеры; core не знает их типы.
Time-travel — debug tooling; направление зависимости защищает `checkDebugGraph`.

Правило размещай у владельца данных. Не дублируй переход в UI и Store и не вводи
«универсальный repository/use case слой», если библиотека не решает такую задачу.
Общая логика остаётся в commonMain, платформенная — за текущим контрактом/expect-actual.
Сохраняй source compatibility, ABI, exception/cancellation semantics и формат записей.
Новая публичная абстракция оправдана потребителем, а не желанием применить Clean Architecture.

Для выбора конкретных ответственностей читай
[high-cohesion-low-coupling](../high-cohesion-low-coupling/SKILL.md).
