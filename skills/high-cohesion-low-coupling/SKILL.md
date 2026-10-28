---
name: high-cohesion-low-coupling
description: "Проектирование Actron: ответственность, владельцы фактов, GRASP/SOLID, API, состояние и жизненный цикл. Читать до проектного решения."
---

# Ответственности и владельцы фактов

Адаптировано из одноимённого skill KMP Messenger. Прочитай до выбора класса/функции,
API, состояния, зависимости или lifecycle. Механическое форматирование этого не требует.
Назови применённое правило при первом проектном решении.

Определи гарантию единицы, владельца данных и контракт потребителя: вход, результат,
ошибку, отмену и завершение. Держи одно изменяемое представление факта; производное
значение вычисляй. Кеш требует причины и контракта актуальности.

В core:

- `StoreImpl` — фасад Store: публичные вызовы и caller-owned коллекция StateFlow с lazy startup.
  `StoreCompanionApi` адаптирует отдельный контракт companion-модулей к тому же процессору;
  patch возвращает исходный фасад. Store задаёт обязательный контракт отменяемых callback-наблюдателей
  и closeAndJoin. Handle отменяет только Job наблюдателя. State регистрируется асинхронно;
  observeEvent регистрирует collector до возврата handle. Отдельной capability ради старых Store
  и старых callback-обёрток нет: до первого релиза поддерживается только актуальный API.
- `StoreProcessor` владеет mutex, сборкой runtime и допуском startup/dispatch.
  `StoreTransitions` и commit публикации выполняются только под этим mutex.
- `StoreLaunchedWork` связывает state-owned launch с root-owned transaction/recovery входами.
  Он использует тот же mutex: проверяет допуск и читает snapshot под lock, после допуска
  отмена launch не разрывает транзакцию. Close по-прежнему отменяет root job.
  Ошибка launch ставит отдельный recovery input с исходным origin; если state вышел или
  запрос отменён до обработки, ошибка сообщается без восстановления чужого состояния.
  Создание объекта пассивно. Job, lanes и subscriptions остаются у StoreExecution/StateRuntime;
  DSL-снимки и eligibility predicate — у StoreLaunchedScopes, outcome/revision — у StoreInputs.
- `StorePublication` — единственный владелец MutableStateFlow и MutableSharedFlow, lazy restore
  и порядка эффектов публикации. Commit выполняет StoreInputs.commit (publish → revision/trace),
  затем saver и onState. Emit ожидает доставку события, затем trace и onEvent под marker Store.
  Два callback hooks проходят через общий StorePlugins; отдельного plugin lock здесь нет.
  Ошибка после публикации сообщается по прежней политике. Revision/outcome остаются у StoreInputs;
  event из launch не захватывает transition mutex и сохраняет InputOrigin своего запуска.
- `StoreTransitions` владеет алгоритмом exit → commit → enter, глубиной enter-цепочки,
  выбором recovery и валидацией восстановленного состояния. Его узкий порт эффектов не даёт
  доступа к mutex, очереди или полям координатора; он читает уже committed state для recovery.
- `StoreReporting` владеет изоляцией probes, FailureReported и однократным вызовом handler.
  Он получает attribution явно и не принимает решения о переходе либо recovery.
- `StoreExecution` владеет корневым Job и marker конкретного Store. Запрещён self-await,
  но обработчик может ожидать другой Store. Создание объекта не запускает coroutine scope.
  closeAndJoin использует отдельный marker владения root: он охватывает также launches и
  collectors, переживает NonCancellable и отклоняет ожидание себя до отмены Store.
  Адаптеры machine сохраняют свой StoreLifetime marker при DSL/patch настройке context.
- `StoreStartup` владеет успешными фазами первоначального запуска. Завершённый onStart
  не повторяется при retry, неуспешный initial enter не превращается в бесконечный startup.
- `StoreIdleWaiter` наблюдает pending inputs и конечные state launches под тем же mutex;
  timeout-report не ждёт занятую блокировку. Само ожидание не стартует Store.
  При отмене root Job StoreExecution отдаёт барьер его завершения: idle учитывает cleanup
  всех корутин, включая subscriptions и plugin launches; отдельного флага close нет.
- `StoreDispatchQueue` владеет порядком dispatch, активным обработчиком, completion-сигналом
  и отменой ожидающих dispatch. Не жди полного завершения Job обработчика ради следующего входа:
  его дочерняя корутина может жить дольше обработки под mutex.
- `StoreConfiguration` владеет runtime-настройками и границами их потребления. Проверка всего
  patch предшествует изменениям. Чтение state, использование context и запрет patch после
  запроса startup — разные ограничения; успешная инициализация Store не заменяет эти факты.
  Builder хранит последние команды настройки, runtime-конфигурация получает их при сборке.
- `QueuedStoreInput` отличает отмену ожидающего входа от обработки под mutex; терминальный
  trace и pending count по-прежнему принадлежат `StoreInputs`.
- `StoreInputs` владеет идентичностью входов, pending count, ordinal, revision и outcome.
- `StorePlugins` владеет сериализацией hook-раундов, успешными startup-регистрациями и
  адаптерами PluginScope. Plugin launch читает свежий state, сохраняя input-origin запуска.
- `StoreStateScopes` владеет таблицей активаций и создаёт контексты enter/action/exit/recover.
  Снимок и предложенный следующий state локальны конкретному вызову обработчика.
- `StoreLaunchedScopes` сохраняет action и Job запустившей корутины, адаптирует launch/transaction
  DSL. Свежий snapshot транзакции выдаёт StoreLaunchedWork после получения общего mutex.
- `StoreScopeExecution` — узкий внутренний контракт эффектов. Не передавай контекстам весь
  StoreImpl или доступ к его mutable полям. Mutex принадлежит StoreProcessor, допуском
  transaction/recovery под ним занимается StoreLaunchedWork, переходами — StoreTransitions.
- `StateRuntime` владеет корутинами конкретной активации состояния и launch lanes.
  Завершённые subscriptions удаляются через cleanup под mutex, включая завершение до регистрации
  completion callback. Живые subscriptions по-прежнему исключены из обычного awaitIdle.
- `HandlerRegistry` владеет замороженными исполняемыми регистрациями и validators. First-match,
  hasAction, metadata и matches используют одни записи; introspection не запускает handlers.
  StoreBuilder сохраняет DSL и прежние @PublishedApi wrappers, передавая реестру snapshot при build.
  Plugins/probes получают отдельные mutable-копии в runtime-конфигурации для штатного patch.
  Поздние изменения сохранённого builder не влияют на построенный Store. Это поверхностный
  snapshot: сами plugin/saver/handler объекты и захваченные ими пользовательские данные не клонируются.
- `StoreBoundaryFailure` — внутренний сигнал обхода DSL recovery, не пользовательский fatal error.
  handlerFailed обозначает ошибку уже вызванного exception handler: повторно сообщать её нельзя.
  boundaryCause снимает только один собственный wrapper и сохраняет пользовательские cause chains.

У переноса должна быть содержательная причина: отделяется ответственность и её данные,
а не просто кусок строк в Utils/Manager. Сильная зависимость от порядка, алгоритма или
lock должна оставаться внутри одного владельца либо быть явной в его контракте.

## Decompose boundary

`actron-decompose` depends on core, our StateChart implementation and Decompose, never the reverse.
`retainedMachine` retains one MachineInstance through InstanceKeeper; it owns the Store and command
scope and closes/cancels them at keeper destruction. Lifecycle-bound observation
belongs to the current component: old endpoints/callbacks are released on destroy without closing
retained Store. The component exposes Store state or an immutable projection, not a second writable
state owner. Keep component/Bridge references out of retained handlers. Process-death serialization
remains with the app's StateKeeper/StateSaver; the adapter does not invent a persistence schema.
Lifecycle observation registration/close uses the lifecycle's owning thread; callbacks keep Store
execution context. Event registration must be ready before startup can emit transient events.

## GRASP

| Эвристика | Применение к библиотеке |
|---|---|
| Information Expert | Правило у владельца данных: pending/outcome у входа, startup registrations у плагинов |
| Creator | Store создаёт свой runtime; конфигурация собирает зависимости, не глобальный singleton |
| Controller | Store принимает dispatch и координирует, передавая отдельные правила владельцам |
| Low Coupling / High Cohesion | Внутренняя единица решает одну задачу и знает минимум о соседях |
| Polymorphism | Существующий Plugin/StateSaver/ExceptionHandler закрывает реальную вариативность |
| Pure Fabrication | Технический runtime допустим, когда он сохраняет cohesion моделей и Store |
| Indirection | Порт/адаптер отделяет платформу или сторонний SDK от core |
| Protected Variations | Compose, диагностика и persistence приложения остаются снаружи core |

## SOLID и паттерны

SRP — одна причина изменения, а не один метод. OCP — используй существующие точки
расширения; не проектируй интерфейс для единственной функции «на будущее».
LSP — сохраняй ошибки, отмену, порядок hooks и lifecycle для всех реализаций контракта.
ISP — потребитель видит нужные ему операции, без доступа к mutable внутренностям соседа.
DIP — core задаёт контракты, адаптеры зависят от них; не тащи UI/SDK в commonMain.

Используй State, Strategy, Observer, Factory и Null Object там, где уже есть соответствующее
поведение. Не превращай название паттерна в требование создать класс.

## Проверка решения

- Не размножились ли владельцы, флаги, predicatеs и скрытые эффекты?
- Может ли тест управлять причиной и наблюдать поломку через production-вход?
- Сохранились ли порядок, родительские Job, structured concurrency и обработка отмены?
- Не стал ли простой вызов протоколом из нескольких обязательных вызовов?
- Остались ли immutable snapshots там, где контракт обещает снимок?

Сценарии на границах: cancelled launch не коммитит результат; успешный onStart не повторяется
при retry другого plugin; каждый принятый input получает terminal trace; чужая ошибка не
превращается в success. Не усиливай инварианты за пределами существующего контракта.
Покажи существенный trade-off; зелёный Detekt — только структурный сигнал.
