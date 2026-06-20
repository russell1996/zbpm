# Бэклог: полная поддержка конструкций BPMN 2.0

Цель — довести движок до полного покрытия исполняемых конструкций BPMN 2.0.
Документ — источник задач; каждую задачу делаем отдельной веткой с тестами и
BPMN-фикстурами в `zorrobpm-engine/src/test/files`.

> **Прогресс (T0+T1):** ✅ выполнены BPMN-01/02 (парсинг event-definition'ов + реестр),
> BPMN-03 (propagation по scope), BPMN-10 (error end), BPMN-11 (error boundary),
> BPMN-12 (message start), BPMN-13 (timer start), BPMN-14 (message boundary),
> BPMN-15 (non-interrupting boundary). ⏸ Отложены: BPMN-16/17 (multi-instance —
> требует scoped-переменных), BPMN-04 (signal-инфра — делается с T2 signal catch/throw).
>
> **Прогресс (T2):** ✅ BPMN-24 (send task = message throw в форме задачи),
> BPMN-25 (receive task = message catch / wait-state в форме задачи),
> BPMN-04 (signal_subscriptions + broadcast 1:N), BPMN-22 (signal catch/throw),
> BPMN-23 (signal start + signal boundary, прерывающий/непрерывающий) и
> BPMN-27 (escalation throw/end + escalation boundary, прерывающий/непрерывающий)
> BPMN-21 (event-based gateway — гонка catch-событий, первое отменяет остальные)
> и BPMN-20 (inclusive gateway — split по всем истинным веткам + default, динамический join).
>
> **Прогресс (T3):** ✅ BPMN-34 (link catch/throw — внутрипроцессный «goto» по имени link).
> Замечание по BPMN-27: escalation throw внутри встроенного подпроцесса не
> поддержан (модель подпроцесса не парсит intermediate-события); используйте
> escalation end внутри подпроцесса либо throw на верхнем уровне / в call activity.

## Легенда

- **Приоритет / Tier**: `T0` фундамент → `T1` критичное → `T2` частое → `T3` расширения.
- **Оценка**: `S` ≈ 1–2 дня, `M` ≈ 3–5 дней, `L` ≈ 1–2 недели.
- **Статус**: `TODO` / `IN PROGRESS` / `DONE`.

Общие критерии приёмки (применяются к каждой задаче, если не указано иное):

1. **Парсинг** — конструкция читается в `BpmnParseServiceImpl` и моделируется в `bpmn/xml` + `bpmn/model`.
2. **Исполнение** — есть обработчик в `ActivityServiceImpl` (или соответствующем сервисе) с корректной семантикой BPMN.
3. **Персистентность** — при необходимости новая таблица/колонка через Liquibase changeset.
4. **Тесты** — unit (`ActivityServiceImplTests`) + интеграционный (`*IntegrationTests`) с `.bpmn`-фикстурой; happy-path и негативный сценарий.
5. **Идемпотентность/конкурентность** — новые точки возобновления берут per-instance lock (`lockAndReload`), как существующие.
6. **Документация** — обновлена матрица поддержки в `README.md`.

---

## Tier 0 — Фундамент (блокирует большинство задач T1–T3)

### BPMN-01 — Обобщённый парсинг event-definition
**T0 · M · TODO**
Расширить XML-модели событий (start/end/boundary/intermediate) чтением определений:
`errorEventDefinition`, `signalEventDefinition`, `escalationEventDefinition`,
`conditionalEventDefinition`, `linkEventDefinition`, `compensateEventDefinition`.
Сейчас читаются только `message`, `timer`, `terminate`.
**Приёмка**: парсер выставляет корректный `BpmnElementType`/extension для каждого
типа определения; неизвестные определения не роняют парсинг; покрыто unit-тестами парсера.

### BPMN-02 — Реестр деклараций уровня definitions
**T0 · S · TODO · зависит: BPMN-01**
Читать `<bpmn:error>`, `<bpmn:signal>`, `<bpmn:escalation>` на уровне `definitions`
(как уже делается для `<bpmn:message>`) и резолвить `xxxRef` в имя/код.
**Приёмка**: `errorRef`/`signalRef`/`escalationRef` корректно резолвятся в код/имя.

### BPMN-03 — Распространение событий вверх по иерархии scope
**T0 · L · TODO · зависит: BPMN-01**
Инфраструктура поиска обработчика события (error/escalation) по дереву scope
(токены/подпроцессы → процесс), отмена активных активностей в прерванном scope,
продолжение по потоку обработчика. Основа для error/escalation boundary и event sub-process.
**Приёмка**: брошенная ошибка находит ближайший подходящий обработчик в родительских
scope; при отсутствии — поведение по умолчанию (incident); покрыто тестами на 2 уровнях вложенности.

### BPMN-04 — Подписки на signal
**T0 · M · DONE · зависит: BPMN-01, BPMN-02**
Таблица `signal_subscriptions` (по аналогии с `message_subscriptions`) + сервис
broadcast-доставки сигнала всем активным подписчикам (в отличие от message — 1:1).
**Приёмка**: миграция Liquibase; broadcast будит все ожидающие активности; конкурентная
доставка защищена per-instance lock; тесты.

---

## Tier 1 — Критичное для реальных процессов

### BPMN-10 — Error end event
**T1 · S · TODO · зависит: BPMN-01, BPMN-02, BPMN-03**
End-событие с `errorEventDefinition`: бросает бизнес-ошибку, которую ловит error boundary
вверх по scope.
**Приёмка**: процесс с error-end + ловящим boundary уходит по ветке обработки; без ловца — incident.

### BPMN-11 — Error boundary event (прерывающий)
**T1 · M · TODO · зависит: BPMN-03, BPMN-10**
Boundary-ошибка на сервис-задаче/подпроцессе: при ошибке (или error-end дочернего scope)
хост отменяется, поток идёт по boundary. **Самый востребованный недостающий элемент** —
сейчас падение сервис-задачи становится только инцидентом.
**Приёмка**: падение воркера с заданным errorCode уходит по error-boundary; несовпадающий
код → incident; тесты на match/no-match.

### BPMN-12 — Message start event (исполняемый)
**T1 · M · TODO · зависит: BPMN-01, BPMN-04-подобный механизм message**
При деплое процесса с message-start создаётся подписка; корреляция сообщения **создаёт
новый экземпляр**. Поправить `checkBpmn` (сейчас требует ровно один «обычный» start —
процесс только с message-start не деплоится).
**Приёмка**: отправка сообщения стартует экземпляр; версионирование учитывает подписку
только последней версии; тесты.

### BPMN-13 — Timer start event (исполняемый)
**T1 · M · TODO · зависит: BPMN-01**
Timer-start (cycle/date/duration) планирует запуск экземпляра по расписанию через
`TimerScheduler`. Поправить `checkBpmn`.
**Приёмка**: экземпляр создаётся по наступлению срока; cron-cycle поддержан; тесты.
**Замечание**: при многоинстансности нужен `SKIP LOCKED`/leader (см. техдолг аудита).

### BPMN-14 — Boundary message event
**T1 · M · TODO · зависит: BPMN-03, BPMN-04**
Сообщение-boundary на задаче/подпроцессе, прерывающий и непрерывающий варианты.
**Приёмка**: корреляция сообщения прерывает/не прерывает хост согласно `cancelActivity`; тесты.

### BPMN-15 — Non-interrupting timer boundary
**T1 · S · TODO · зависит: существующий timer boundary**
Непрерывающий вариант таймер-boundary: хост продолжает жить, boundary порождает
параллельную ветку (новый токен).
**Приёмка**: хост не отменяется; ветка boundary исполняется параллельно; тесты.

### BPMN-16 — Multi-instance (параллельный)
**T1 · L · TODO**
`multiInstanceLoopCharacteristics` (isSequential=false) на задаче/подпроцессе:
размножение по коллекции, N параллельных экземпляров, агрегирующий join, `loopCounter`/
`nrOfInstances`/`nrOfActiveInstances`, `completionCondition`.
**Приёмка**: коллекция из N → N инстансов; завершение по всем (или по completionCondition);
переменные элемента (`inputElement`/`outputElement`) мапятся; тесты.

### BPMN-17 — Multi-instance (последовательный)
**T1 · M · TODO · зависит: BPMN-16**
isSequential=true: экземпляры по очереди, по одному токену за раз.
**Приёмка**: строго последовательное исполнение; completionCondition прерывает цикл; тесты.

---

## Tier 2 — Частые конструкции

### BPMN-20 — Inclusive gateway (split/join)
**T2 · L · DONE**
Замечание: поддержан канонический ромб (один inclusive split → один inclusive join,
возможно с промежуточными задачами/wait-state). Join ждёт ровно столько прибытий,
сколько веток активировал split (счётчик в `parallel_gateways.expected_count`).
Экзотические топологии (несколько split в один join, слияние веток до join) — позже.
Split: запускает все ветки с истинным условием (плюс default). Join: ждёт все активные
входящие ветки (сложнее parallel — число ожидаемых динамическое).
**Приёмка**: корректный inclusive join по реально активным веткам; тесты на разветвление 1/2/все.

### BPMN-21 — Event-based gateway
**T2 · M · DONE · зависит: message/timer/signal catch**
Гонка между несколькими catch-событиями (message/timer/signal): срабатывает первое,
остальные отменяются.
**Приёмка**: первое наступившее событие продолжает поток, прочие подписки/таймеры снимаются; тесты.

### BPMN-22 — Signal catch / throw
**T2 · M · DONE · зависит: BPMN-04**
Промежуточный signal catch (подписка) и throw (broadcast всем подписчикам процесса/глобально).
**Приёмка**: throw будит всех ожидающих; тесты на нескольких подписчиков.

### BPMN-23 — Signal start + signal boundary
**T2 · M · DONE · зависит: BPMN-04, BPMN-03**
Signal-start (broadcast стартует экземпляры) и signal-boundary (прерывающий/непрерывающий).
**Приёмка**: сигнал стартует все подписанные определения и прерывает подписанные активности; тесты.

### BPMN-24 — Send task
**T2 · S · DONE**
Send task = семантика message-throw в форме задачи (публикация/корреляция сообщения).
Убрать «мёртвый» `SEND_TASK` из enum либо реализовать его.
**Приёмка**: парсинг + исполнение как throw; тесты.

### BPMN-25 — Receive task
**T2 · S · DONE · зависит: message catch**
Receive task = message catch в форме задачи (wait-state + подписка).
Реализовать `RECEIVE_TASK` (сейчас мёртвый в enum).
**Приёмка**: парковка до корреляции сообщения; тесты.

### BPMN-26 — Event sub-process
**T2 · L · TODO · зависит: BPMN-03**
Встроенный event-subprocess с триггерами (message/timer/error/signal/escalation),
прерывающий и непрерывающий относительно родительского scope.
**Приёмка**: прерывающий отменяет родительский scope, непрерывающий — параллелен; тесты.

### BPMN-27 — Escalation throw / boundary / end
**T2 · M · DONE · зависит: BPMN-03**
Escalation как «некритичная» ошибка (не прерывает обязательно): throw, boundary
(interrupting/non-interrupting), escalation-end.
**Приёмка**: распространение по scope как у error, но non-interrupting по умолчанию; тесты.

---

## Tier 3 — Расширения

### BPMN-30 — Script task
**T3 · M · TODO**
Inline-скрипт (FEEL и/или JS через GraalJS) с доступом к переменным и записью результата.
**Приёмка**: вычисление и запись переменных; sandbox/таймаут исполнения; тесты.

### BPMN-31 — Business rule task (DMN)
**T3 · L · TODO**
Исполнение DMN-таблиц (например, через camunda-dmn-engine). Деплой DMN, привязка к задаче.
**Приёмка**: задача исполняет DMN-решение и пишет результат в переменные; тесты.

### BPMN-32 — Compensation
**T3 · L · TODO · зависит: BPMN-03**
Compensation boundary + compensation throw + откат завершённых активностей в обратном порядке.
**Приёмка**: throw компенсации запускает зарегистрированные хендлеры завершённых задач; тесты.

### BPMN-33 — Transaction sub-process + Cancel
**T3 · M · TODO · зависит: BPMN-32**
Transaction-subprocess, cancel-end и cancel-boundary, запуск компенсации при отмене.
**Приёмка**: cancel-end откатывает транзакцию через компенсацию; тесты.

### BPMN-34 — Link events (catch/throw)
**T3 · S · DONE · зависит: BPMN-01**
Link throw → соответствующий link catch (внутрипроцессный «goto» для удобства моделирования).
**Приёмка**: throw продолжает поток от парного catch по имени link; тесты.

### BPMN-35 — Conditional events
**T3 · M · TODO · зависит: BPMN-01**
Conditional start/catch/boundary: срабатывание при истинности FEEL-условия на данных.
**Приёмка**: изменение переменных, делающее условие истинным, будит/стартует; тесты.

### BPMN-36 — Data input/output mappings
**T3 · M · TODO**
IO-маппинги на уровне задач (`zeebe:ioMapping` input/output), локальные переменные scope
вместо плоских на весь экземпляр; пропагация в/из call activity по маппингу.
**Приёмка**: input/output трансформации применяются; локальные переменные не «протекают»; тесты.

### BPMN-37 — Корреляция сообщений по ключу
**T3 · M · TODO · зависит: message catch/start**
Correlation key (по значению переменной), а не только по имени сообщения; точечная доставка
в нужный экземпляр.
**Приёмка**: сообщение коррелируется по ключу к конкретному экземпляру; тесты на коллизии имён.

---

## Сводная таблица

| ID | Конструкция | Tier | Оценка | Зависимости |
|----|-------------|------|--------|-------------|
| BPMN-01 | Парсинг event-definition | T0 | M | — |
| BPMN-02 | Реестр error/signal/escalation | T0 | S | 01 |
| BPMN-03 | Propagation по scope | T0 | L | 01 |
| BPMN-04 | Подписки на signal | T0 | M | 01,02 |
| BPMN-10 | Error end | T1 | S | 01,02,03 |
| BPMN-11 | Error boundary | T1 | M | 03,10 |
| BPMN-12 | Message start (exec) | T1 | M | 01 |
| BPMN-13 | Timer start (exec) | T1 | M | 01 |
| BPMN-14 | Message boundary | T1 | M | 03,04 |
| BPMN-15 | Non-interrupting timer boundary | T1 | S | — |
| BPMN-16 | Multi-instance параллельный | T1 | L | — |
| BPMN-17 | Multi-instance последовательный | T1 | M | 16 |
| BPMN-20 | Inclusive gateway | T2 | L | — |
| BPMN-21 | Event-based gateway | T2 | M | catch-события |
| BPMN-22 | Signal catch/throw | T2 | M | 04 |
| BPMN-23 | Signal start/boundary | T2 | M | 03,04 |
| BPMN-24 | Send task | T2 | S | — |
| BPMN-25 | Receive task | T2 | S | message catch |
| BPMN-26 | Event sub-process | T2 | L | 03 |
| BPMN-27 | Escalation | T2 | M | 03 |
| BPMN-30 | Script task | T3 | M | — |
| BPMN-31 | Business rule (DMN) | T3 | L | — |
| BPMN-32 | Compensation | T3 | L | 03 |
| BPMN-33 | Transaction + Cancel | T3 | M | 32 |
| BPMN-34 | Link events | T3 | S | 01 |
| BPMN-35 | Conditional events | T3 | M | 01 |
| BPMN-36 | Data IO mappings | T3 | M | — |
| BPMN-37 | Корреляция по ключу | T3 | M | message |

## Примечания

- **Порядок**: T0 целиком → затем T1. T0 — общая инфраструктура (парсинг определений,
  propagation по scope, подписки), без неё T1–T3 будут дублировать код.
- **Грубая оценка** всего бэклога: ~8–12 недель работы итерациями (T0+T1 ≈ половина).
- **Модель исполнения**: multi-instance (16/17), inclusive join (20), event-subprocess (26)
  и compensation (32) затрагивают модель токенов/scope — закладывать запас на рефакторинг
  синхронного обхода.
- **Связь с техдолгом аудита**: timer-start (13) и signal broadcast (04/23) усиливают
  необходимость многоинстансной безопасности таймеров (`SKIP LOCKED`/leader-election) —
  отдельная задача вне этого бэклога.
