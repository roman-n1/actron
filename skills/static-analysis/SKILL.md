---
name: static-analysis
description: "Настройка и диагностика ktlint/Detekt Actron: общий config, точный долг и blocking CI без подавления новых нарушений."
---

# Статический анализ

Адаптирован из static-analysis KMP Messenger. Источник правды —
[code-style](../../doc/guides/code-style.md), `.editorconfig`, `gradle/ktlint.gradle.kts`,
`gradle/detekt.gradle.kts`, `config/detekt/detekt.yml` и CI.
Версии совпадают с исходным проектом: ktlint 1.8.0, Detekt 2.0.0-alpha.6.

- Формат проверяет ktlint, smells/bugs/complexity — Detekt без formatting ruleset.
- `./gradlew ktlintCheck detekt`; модульно — `./gradlew :actron-core:detekt`.
- Анализируются все source sets модулей, common и платформенные, main и test.
  Included builds и standalone tooling пока покрыты ktlint, но не module Detekt.
- Plain CLI не выполняет правила, требующие type resolution; зелёный light-анализ не
  является полным семантическим анализом или архитектурным review.
- Baseline каждого модуля в `config/detekt/baselines/` — первичный точный долг.
  Только сокращай его. Не создавай новый baseline вместо исправления находок.
- `detektCreateBaseline` — инструмент первичного подключения, не штатное исправление CI.
  `checkDetektBaselines` блокирует рост относительно HEAD или `-Pactron.quality.base=<revision>`;
  CI передаёт базу PR/предыдущего push. При самом первом подключении конфигурации долга ещё нет.
- Разбери rule-id, место и смысл. Не поднимай порог, не добавляй suppress/exclude ради GREEN.
  Исправление false positive требует объяснения и узкого, проверяемого изменения конфигурации.
- Для LongMethod/LargeClass/TooManyFunctions найди владельцев и причины изменения;
  не режь по строкам. Тесты исключены из счётчиков размера как в KMP; корректность проверяется.
- Отчёты SARIF — `<module>/build/reports/detekt/detekt.sarif`.

До изменения самого правила — позитивный и негативный контроль реальным движком.
Не перезапускай уже проверенный неизменный периметр без причины.

Compose boolean policy: `./gradlew checkComposeNoBooleanLogic` uses Kotlin PSI and runs with
`detekt`. The exact initial inventory is `config/compose/boolean-logic-baseline.tsv`;
`:nullability-guard:pruneComposeBooleanBaseline` can only remove resolved entries.
Tests cover forbidden/allowed syntax and multiplicity/replacement of baseline entries.
See [Compose presentation](../compose-presentation/SKILL.md) for ownership and scope.
