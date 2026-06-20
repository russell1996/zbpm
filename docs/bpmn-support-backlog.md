# Бэклог: полная поддержка конструкций BPMN 2.0

Цель — довести движок до полного покрытия исполняемых конструкций BPMN 2.0.
Документ — источник задач; каждую задачу делаем отдельной веткой с тестами и
BPMN-фикстурами в `zorrobpm-engine/src/test/files`.

> **Прогресс (T0+T1):** ✅ выполнены BPMN-01/02 (парсинг event-definition'ов + реестр),
> BPMN-03 (propagation по scope), BPMN-10 (error end), BPMN-11 (error boundary),
> BPMN-12 (message start), BPMN-13 (timer start), BPMN-14 (message boundary),
> BPMN-15 (non-interrupting boundary), BPMN-16 (multi-instance — **частично**: параллельный
> на user task по `loopCardinality` + агрегирующий join; последовательный/коллекции/loopCounter отложены).
> ⏸ Отложены: BPMN-17 (sequential multi-instance), per-instance переменные (требуют scoped-переменных).
>
> **Прогресс (T2):** ✅ BPMN-26 (event sub-process — top-level, message-триггер,
> прерывающий и непрерывающий; не-message-триггеры/вложенность отложены; см. ниже),
> BPMN-24 (send task = message throw в форме задачи),
> BPMN-25 (receive task = message catch / wait-state в форме задачи),
> BPMN-04 (signal_subscriptions + broadcast 1:N), BPMN-22 (signal catch/throw),
> BPMN-23 (signal start + signal boundary, прерывающий/непрерывающий) и
> BPMN-27 (escalation throw/end + escalation boundary, прерывающий/непрерывающий)
> BPMN-21 (event-based gateway — гонка catch-событий, первое отменяет остальные)
> и BPMN-20 (inclusive gateway — split по всем истинным веткам + default, динамический join).
>
> **Прогресс (T3):** ✅ BPMN-33 (transaction sub-process + cancel — cancel-end компенсирует транзакцию и
> уходит по cancel-boundary; вложенные транзакции отложены),
> BPMN-32 (compensation — compensation boundary + intermediate throw, откат
> завершённых compensation-bounded активностей в обратном порядке; targeted/в подпроцессе отложены),
> BPMN-36 (data IO mappings — `zeebe:ioMapping` input/output как FEEL-трансформации
> на service/user task; строгая scope-локальность отложена, переменные плоские),
> BPMN-34 (link catch/throw — внутрипроцессный «goto» по имени link),
> BPMN-30 (script task — inline FEEL-выражение, результат пишется в переменную; ошибка скрипта → инцидент),
> BPMN-35 (conditional catch/boundary — FEEL-условие на данных, реоценка при изменении переменных;
> conditional **start** отложен — нужен кросс-instance триггер),
> BPMN-37 (корреляция сообщений по ключу — `zeebe:subscription correlationKey`, точечная доставка
> нужному экземпляру среди коллизий по имени; message-start с ключом отложен).
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
**T1 · L · DONE (частично: user task по loopCardinality)**
`multiInstanceLoopCharacteristics` (isSequential=false) на **user task**: по `loopCardinality` (литерал
или FEEL) порождается N параллельных инстансов (N активностей на одном токене); агрегирующий join ждёт
все N завершений и затем продолжает поток. Счётчик завершений переиспользует механизм прибытий
parallel/inclusive gateway (ключ — id активности инстанса, уникальный).
Отложено (отдельные заходы): размножение по **input-коллекции**, `inputElement`/`outputElement` и
`loopCounter` (требуют scoped-переменных — плоская модель не изолирует per-instance состояние),
`completionCondition`, multi-instance на **подпроцессе** и на **service task**.
**Приёмка**: `loopCardinality`=N → N инстансов; завершение по всем; тест (3 инстанса, join после 3-го).

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
**T2 · L · DONE (top-level, message-триггер, прерывающий и непрерывающий) · зависит: BPMN-03**
Встроенный `<subProcess triggeredByEvent="true">` с message-стартом (`isInterrupting`). При деплое его
стартовое событие помечается (`eventSubProcessId`) и исключается из process-level message-стартов; при
старте экземпляра регистрируется instance-scoped подписка (`message_subscriptions.event_subprocess_id`,
`activity_id` стал nullable). Корреляция сообщения запускает обработчик:
- **прерывающий**: подписка потребляется, активные активности основного потока отменяются
  (`cancelActiveActivities`), обработчик выполняется на свежем токене — его end-событие завершает экземпляр;
- **непрерывающий**: подписка сохраняется (может сработать снова), основной поток продолжается, обработчик
  выполняется в собственном subprocess-scope (scope-токен) — его end завершает только scope (см. `finishBranch`).

Отложено (отдельные заходы): триггеры **timer/error/signal/escalation**, event-subprocess **внутри
встроенного подпроцесса** (не top-level). Замечание: при непрерывающем обработчике, переживающем основной
поток, экземпляр завершается на первом top-level end (общий лимит параллельных веток без join).
**Приёмка**: прерывающий отменяет основной поток и завершает экземпляр обработчиком; непрерывающий работает
параллельно и может сработать многократно; без сообщения основной поток завершается штатно; тесты.

### BPMN-27 — Escalation throw / boundary / end
**T2 · M · DONE · зависит: BPMN-03**
Escalation как «некритичная» ошибка (не прерывает обязательно): throw, boundary
(interrupting/non-interrupting), escalation-end.
**Приёмка**: распространение по scope как у error, но non-interrupting по умолчанию; тесты.

---

## Tier 3 — Расширения

### BPMN-30 — Script task
**T3 · M · DONE**
Inline-скрипт на FEEL (`<bpmn:script>` + `scriptFormat="feel"`) вычисляется синхронно в потоке токена;
результат пишется в переменную `resultVariable` (тип выводится: Boolean→BOOLEAN, целое число→LONG,
иначе STRING). Ошибка скрипта (синтаксис/исполнение) оставляет активность незавершённой и поднимает
инцидент, как любой другой сбой элемента. Используется отдельный FEEL-движок выражений
(`FeelScriptEngineFactory`), в отличие от unary-tests движка для условий потоков.
Замечание: JS/GraalJS и sandbox/таймаут исполнения — отдельным заходом при необходимости.
**Приёмка**: вычисление и запись переменных; тесты (числовой/булев/строковый результат + невалидный скрипт→инцидент).

### BPMN-31 — Business rule task (DMN)
**T3 · L · TODO**
Исполнение DMN-таблиц (например, через camunda-dmn-engine). Деплой DMN, привязка к задаче.
**Приёмка**: задача исполняет DMN-решение и пишет результат в переменные; тесты.

### BPMN-32 — Compensation
**T3 · L · DONE (throw + boundary, обратный порядок) · зависит: BPMN-03**
Compensation boundary (привязка хендлера к активности через `<bpmn:association>`) + промежуточный
compensation throw. Throw находит завершённые compensation-bounded активности экземпляра и выполняет их
хендлеры в обратном порядке (по `createdAt` DESC — для линейного потока это обратный порядок завершения),
синхронно на токене throw, затем продолжает по своему исходящему потоку. Хендлер — отдельная активность вне
основного потока (без входящего потока), исполняется только при компенсации; `proceedToOutgoing` сделан
null-safe для хендлеров без исходящего потока.
Отложено (отдельные заходы): targeted-компенсация (по `activityRef`), compensation **end**-событие,
компенсация в scope подпроцесса, асинхронные service-task хендлеры (детерминированный реверс требует
явного порядка вместо `createdAt`).
**Приёмка**: throw компенсации запускает хендлеры завершённых задач в обратном порядке; тест с
арифметической кодировкой порядка (B→A над log=0 даёт 21, не 12).

### BPMN-33 — Transaction sub-process + Cancel
**T3 · M · DONE (top-level transaction) · зависит: BPMN-32**
`<bpmn:transaction>` исполняется как встроенный подпроцесс (тот же POJO/flattening/scope-токен; cancel-
семантику несут cancel-end и cancel-boundary, а не тип контейнера). **Cancel end** внутри транзакции:
компенсирует завершённые активности транзакции (по scope-токену, в обратном порядке — переиспользует
`runCompensation` из BPMN-32), отменяет scope (контейнер + активные активности токена) и продолжает поток
от **cancel boundary** транзакции (прерывающий). Нормальный end транзакции при этом не достигается.
Отложено: вложенные транзакции / распространение cancel наружу.
**Приёмка**: cancel-end откатывает транзакцию через компенсацию и уходит по cancel-boundary; тест с
арифметической кодировкой (txTask +1, компенсация +100, ветка cancel +1000 → log=1101; normalEnd не взят).

### BPMN-34 — Link events (catch/throw)
**T3 · S · DONE · зависит: BPMN-01**
Link throw → соответствующий link catch (внутрипроцессный «goto» для удобства моделирования).
**Приёмка**: throw продолжает поток от парного catch по имени link; тесты.

### BPMN-35 — Conditional events
**T3 · M · DONE (catch/boundary; start отложен) · зависит: BPMN-01**
Conditional **catch** (промежуточный) и **boundary** (прерывающий/непрерывающий): FEEL-условие на данных.
Условие проверяется при входе в catch (если уже истинно — pass-through) и переоценивается после каждой
записи переменных (`completeServiceTask`/`completeUserTask`/`signal`/`fireBoundary`/script task) через
`triggerConditionalEvents`; параллельная ветка, делающая условие истинным, будит ожидающий catch или
поджигает boundary на активном хосте. Re-entrancy guard (ThreadLocal) не даёт продолжению сработавшего
события рекурсивно перезапускать переоценку. Conditional **start** отложен: требует кросс-instance
триггера переоценки при изменении данных (как у message/signal start, но по условию) — отдельный заход.
**Приёмка**: изменение переменных, делающее условие истинным, будит catch / поджигает boundary; тесты
(pass-through на входе, пробуждение параллельной веткой, прерывающий boundary).

### BPMN-36 — Data input/output mappings
**T3 · M · DONE (трансформации; строгая локальность отложена)**
`zeebe:ioMapping` на service/user task: input-маппинги применяются при активации, output — при завершении.
Каждый маппинг вычисляет FEEL-выражение `source` (через expression-движок, см. BPMN-30) и пишет результат
в `target` (`applyIoMappings` в `enterServiceTask`/`enterUserTask` и `completeServiceTask`/`completeUserTask`).
Отложено (отдельный заход): **локальные переменные scope** (сейчас переменные плоские на весь экземпляр —
маппинги «протекают» как instance-переменные, строгая изоляция не реализована) и пропагация в/из call
activity по маппингу.
**Приёмка**: input/output трансформации применяются; тесты (input на активации, output на завершении).

### BPMN-37 — Корреляция сообщений по ключу
**T3 · M · DONE (catch/receive/boundary; message-start с ключом отложен) · зависит: message catch/start**
Correlation key задаётся как `<zeebe:subscription correlationKey="=expr">` на `<bpmn:message>`; FEEL-выражение
вычисляется в контексте подписывающегося экземпляра при создании подписки (через expression-движок,
см. BPMN-30) и сохраняется в `message_subscriptions.correlation_key`. `correlateMessage(name, key, ...)`
доставляет только подпискам с совпадающим ключом — точечно нужному экземпляру среди коллизий по имени.
Без ключа — прежнее поведение (по имени, опц. по экземпляру). Покрывает message catch, receive task и
message boundary; message-**start** с ключом отложен.
**Приёмка**: сообщение коррелируется по ключу к конкретному экземпляру; тесты на коллизии имён
(две инстанции одного определения, ключ выбирает нужную) + несовпадающий ключ никого не будит.

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
| BPMN-16 | Multi-instance параллельный | T1 | L (DONE: user task/cardinality) | — |
| BPMN-17 | Multi-instance последовательный | T1 | M | 16 |
| BPMN-20 | Inclusive gateway | T2 | L | — |
| BPMN-21 | Event-based gateway | T2 | M | catch-события |
| BPMN-22 | Signal catch/throw | T2 | M | 04 |
| BPMN-23 | Signal start/boundary | T2 | M | 03,04 |
| BPMN-24 | Send task | T2 | S | — |
| BPMN-25 | Receive task | T2 | S | message catch |
| BPMN-26 | Event sub-process | T2 | L (DONE: msg interrupt+non-int) | 03 |
| BPMN-27 | Escalation | T2 | M | 03 |
| BPMN-30 | Script task | T3 | M (DONE) | — |
| BPMN-31 | Business rule (DMN) | T3 | L | — |
| BPMN-32 | Compensation | T3 | L (DONE: throw+boundary) | 03 |
| BPMN-33 | Transaction + Cancel | T3 | M (DONE) | 32 |
| BPMN-34 | Link events | T3 | S | 01 |
| BPMN-35 | Conditional events | T3 | M (DONE: catch/boundary) | 01 |
| BPMN-36 | Data IO mappings | T3 | M (DONE: трансформации) | — |
| BPMN-37 | Корреляция по ключу | T3 | M (DONE) | message |

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
