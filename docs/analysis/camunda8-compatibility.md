# ZorroBPM — совместимость с Camunda 8: аналитический отчёт

Дата: 2026-09-02. Read-only исследование, изменений в репозитории не вносилось.

## 1. Резюме

Совместимость по трём осям имеет резко разную стоимость, и находки по Оси 3 (BPMN/DMN) оказались значительно лучше ожидаемых: движок ZorroBPM **уже** парсит расширения `zeebe:` namespace (`http://camunda.org/schema/zeebe/1.0`) для service task/user task/io-mapping/multi-instance, **уже** использует ту же FEEL-engine библиотеку (`org.camunda.feel:feel-engine`, "the same feel-scala line Camunda 8 uses" — комментарий в коде), и DMN-парсер **уже** читает официальный DMN 1.3 namespace (`https://www.omg.org/spec/DMN/20191111/MODEL/`), тот же, что экспортирует Camunda Modeler. Это значит: многие реальные .bpmn/.dmn файлы, экспортированные из Camunda 8 Modeler, вероятно распарсятся ZorroBPM **уже сейчас** без изменений парсера (недели работы на закрытие пробелов — literal-expression decisions, часть boundary/event типов, полная FEEL context-выражения в conditions — не месяцы).

Ось 1 (REST API) — совместимость возможна только на уровне "похожий набор ресурсов", не байт-в-байт совместимый контракт: у нас другая пагинация (offset/limit vs POST-search с курсорами), другой формат ошибок (`{code,message,params}` vs RFC 9457 problem+json), другая аутентификация (собственный JWT/API-key vs OAuth2/OIDC), нет multi-resource `/deployments` (у нас раздельные /process-definitions и /dmn), нет публичного `PublishMessage`-эквивалента. Адаптация под конкретные HTTP-пути реальна за недели, но полная совместимость контракта (клиенты Camunda 8 REST SDK, если такие есть, работающие "из коробки") потребует переписывания половины REST-слоя — это месяцы, и рекомендуется не тратить на это ресурсы, а сделать наш собственный явно документированный API.

Ось 2 (Job Worker протокол) — принципиально другая архитектура: ZorroBPM использует push-модель через RabbitMQ outbox (`ServiceTaskEnqueueServiceImpl` → outbox → `ServiceTaskListener` → очередь по имени job-типа), воркер — это Spring-бин, реализующий `JobHandler`, подключённый к тому же RabbitMQ broker, а не независимый процесс, который поллит gRPC/REST. Существующие Zeebe Java/Go/Node client SDK жёстко закодированы на gRPC-протокол (`gateway.proto`, ~24 RPC) и не заработают против HTTP-обёртки без патча самого SDK. Совместимость "из коробки" с существующими Zeebe-воркерами без модификации клиентского кода реалистична только через полноценный gRPC-gateway поверх нашего движка — это месяцы работы (новый транспортный слой, изменение модели доставки с push/MQ на pull/lease, стриминг), не недели.

**Вывод**: реалистичный горизонт "недели" — только частичная BPMN/DMN-совместимость (закрыть уже узкие пробелы в парсере/FEEL-выполнении, задокументировать какое подмножество zeebe:-схемы поддерживается). REST API и особенно Job-Worker/gRPC-протокол — это горизонт "месяцы+", если вообще целесообразно (см. §5).

---

## 2. Ось 1: REST API

### Общая архитектурная разница (влияет на все ресурсы сразу)

| Аспект | Camunda 8 REST API | ZorroBPM |
|---|---|---|
| Пагинация/поиск | POST-эндпоинты `/xxx/search` с filter-объектом, курсорная модель (по данным официальной документации — не удалось зафетчить точную схему `searchAfter`/`page` полей, предположение основано на общей архитектуре search API Camunda 8) | GET с query-параметрами `pageIndex`/`pageSize` (offset-based) — см. `PagedDataDTO` (`zorrobpm-contract/src/main/java/com/zorrodev/bpm/contract/dto/PagedDataDTO.java`: `pageIndex`, `pageSize`, `totalElements`, `data`) и `ProcessDefinitionsQueryParameters` (`.../contract/dto/ProcessDefinitionsQueryParameters.java`) |
| Формат ошибок | RFC 9457 `application/problem+json` (`type`,`status`,`title`,`detail`,`instance`) — по данным официальной документации | Собственный плоский JSON `{code, message[, params][, correlationId]}` — `GlobalExceptionHandler.java` (`zorrobpm-rest/src/main/java/com/zorrodev/bpm/rest/configuration/GlobalExceptionHandler.java:97-104`) |
| Аутентификация | OAuth2/OIDC Bearer token (client-credentials для сервисов), Basic для dev — по официальной доке | Собственный JWT (`TokenService`) + API-key со своим префиксом `zbpm_sk_` — `JwtAuthFilter.java` (`zorrobpm-rest/src/main/java/com/zorrodev/bpm/rest/security/JwtAuthFilter.java:36`) |
| Версионирование пути | `/v2/...` | нет версионного префикса (пути вида `/process-definitions`, `/process-instances`) |
| Деплой | Один multi-resource `POST /deployments` (bpmn+dmn+form атомарно) | Раздельные пути: bpmn через `ProcessDefinitionContract` (`/process-definitions`), dmn через `DmnService.deploy()` (не видно отдельного REST-эндпоинта деплоя DMN как самостоятельного ресурса — деплоится вместе с процессом), формы через `FormContract` (`/forms`) |

Эта разница в пагинации/ошибках/auth — не точечная правка per-эндпоинт, а сквозная: потребует либо дублирующего "compat-слоя" контроллеров (новый набор ресурсов `/v2/...` поверх тех же сервисов, с адаптером формата ответа/ошибок/пагинации), либо переписывания текущего REST-слоя.

### Таблица по ресурсам

| Camunda 8 ресурс/операция | Наш эквивалент | Совпадение контракта | Оценка усилий |
|---|---|---|---|
| `POST /deployments` (bpmn+dmn+form, атомарно) | `ProcessDefinitionContract.addProcessDefinition` (`POST /process-definitions`, `zorrobpm-contract/src/main/java/com/zorrodev/bpm/contract/ProcessDefinitionContract.java:18-19`) — только BPMN, требует SUPER_ADMIN (`ProcessDefinitionResource.java:70-77`); DMN деплоится отдельно через `DmnService.deploy()` не как отдельный REST-ресурс с версией multi-file | Частично | Средние — новый эндпоинт-обёртка, разбирающий multi-resource payload и делегирующий в существующие сервисы; ~1-2 недели |
| `GET /process-definitions` (search) | `ProcessDefinitionContract.getProcessDefinitions` (`GET /process-definitions`, `ProcessDefinitionContract.java:29-30`) | Частично (ресурс есть, offset/limit вместо filter/search-POST) | Малые — недели |
| `GET /process-definitions/{key}/xml` | `getProcessDefinitionXml` (`ProcessDefinitionContract.java:35-36`, `.../resource/ProcessDefinitionResource.java:180-188`) | Частично (ключ у C8 — bpmnProcessId+version, у нас UUID `id`) | Малые |
| `POST /process-instances` (start) | `RuntimeContract.startProcessInstance` (`POST /process-instances`, `zorrobpm-contract/src/main/java/com/zorrodev/bpm/contract/RuntimeContract.java:17-18`) | Частично — похожий смысл, разные поля (`processDefinitionId`/`processDefinitionKey`/`processDefinitionVersion`+ `List<ProcessVariable>` вместо typed variables map), нет `awaitCompletion`/`CreateProcessInstanceWithResult`-эквивалента | Малые-средние |
| `POST /process-instances/{id}/cancellation` | `RuntimeContract.cancelProcessInstance` (`POST /process-instances/{id}/cancel`, `RuntimeContract.java:42-43`) | Частично (иной путь/verb naming: "cancellation" noun vs "/cancel") | Малые |
| `GET /process-instances` (search) | `QueryContract.getProcessInstances` (`GET /process-instances`, `zorrobpm-contract/src/main/java/com/zorrodev/bpm/contract/QueryContract.java:42-43`) | Частично | Малые |
| User tasks: `GET /user-tasks`, `POST .../completion`, `POST .../assignment` | `QueryContract.getUserTasks` + `RuntimeContract.completeUserTask/claimUserTask/unclaimUserTask/assignUserTask` (`RuntimeContract.java:27-37`) | Частично — операции есть (complete/claim/unclaim/assign), но naming/paths иные (`/user-tasks/{id}/complete` vs Camunda 8 "assignment"/"completion" resource naming) | Малые |
| Incidents: `GET /incidents`, resolve | `QueryContract.getIncidents/getIncident` + `RuntimeContract.resolveIncident` (`RuntimeContract.java:39-40`) | Частично | Малые |
| Variables: read/update | `QueryContract.getVariables` (`GET /variables`, `QueryContract.java:27-28`) | Частично — у нас нет отдельного "update variables"-эндпоинта (аналог `SetVariables`); только через complete/resolve с variables payload | Средние — не хватает независимого set-variables эндпоинта |
| DMN evaluate: `POST /decision-definitions/{key}/evaluation` | `DmnContract.evaluateDecision` (`POST /dmn/{decisionId}/evaluate`, `zorrobpm-contract/src/main/java/com/zorrodev/bpm/contract/DmnContract.java:22-23`, реализация `DmnResource.java:78-90`) | Частично (ресурс/операция есть, path/naming иной, output format иной) | Малые |
| `PUT /messages/{name}/correlation` (publish/correlate message) | **Отсутствует** — есть внутренняя `MessageSubscriptionEntity`/подписки (`QueryContract.getMessageSubscriptions`, `.../message-subscriptions`, только чтение), нет публичного эндпоинта для внешней публикации сообщения | Отсутствует | Средние — нужен новый REST-ресурс + сервисный метод, потребует найти существующий внутренний механизм корреляции (`EventTrigger`, `.../engine/handler/EventTrigger.java`) и открыть его наружу |
| Cluster `GET /topology` | Не найден аналог (архитектурно нерелевантно — у нас не кластер партиций Zeebe) | Отсутствует / неприменимо | Не применимо |
| `GET /jobs`/`POST /jobs/{key}/activation` (REST-based job поллинг, появился в новых версиях Camunda 8 REST API как альтернатива gRPC) | Нет REST job-worker API вообще — доставка задач воркерам идёт через RabbitMQ push, см. Ось 2 | Отсутствует | Крупные — это фактически весь объём Оси 2 |

### Итог по Оси 1
Ресурсно покрытие неплохое (процессы/инстансы/user-tasks/incidents/DMN-evaluation все есть в каком-то виде), но контракт (paths, naming, пагинация, ошибки, auth) нигде не совпадает 1:1. Реалистично за недели: задокументировать наш API как "Camunda 8-inspired", добавить недостающие мелкие операции (set-variables, message publish). Нереалистично в разумные сроки: byte-compatible REST-контракт, который заставил бы существующий Camunda 8 REST-клиент/SDK работать без модификации — это переписывание пагинации/ошибок/auth/deployment-модели по всему REST-слою.

---

## 3. Ось 2: Job Worker протокол

### Архитектура ZorroBPM (как реально устроено сегодня)

1. `ServiceTaskHandler.enter(...)` (`zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/handler/ServiceTaskHandler.java:46-58`) при входе в service task создаёт запись активности и вызывает `serviceTaskEnqueueService.enqueueAfterCommit(activityId)`.
2. `ServiceTaskEnqueueServiceImpl.enqueueAfterCommit` (`.../engine/service/impl/ServiceTaskEnqueueServiceImpl.java:44-100`) строит `JobDetailModel` (переменные, definition/instance/task id, `job` — имя job-типа из `zeebe:taskDefinition`-подобного extension) и пишет его в **transactional outbox table** (не публикует в MQ напрямую — паттерн "outbox" для надёжности, см. комментарий в файле, строки 27-32).
3. Отдельный `OutboxPollerService`/`OutboxBatchProcessor` публикует entry как `ServiceTaskEnqueued` доменное событие.
4. `ServiceTaskListener.on(ServiceTaskEnqueued event)` (`zorrobpm-rabbitmq/src/main/java/com/zorrodev/bpm/rabbitmq/ServiceTaskListener.java:25-49`) отправляет сообщение в **RabbitMQ очередь, названную по имени job-типа** (`JobQueueDeclarer.queueNameFor(detail.getJob())`).
5. Воркер — Spring-приложение, использующее `zorrobpm-job-handler-spring-boot-starter`, реализует интерфейс `JobHandler` (`zorrobpm-job-handler-spring-boot-starter/src/main/java/com/zorrodev/bpm/handler/JobHandler.java:8-13`: `String getJob()` + `List<ProcessVariable> handleJob(JobDetailModel model)`), подписан на ту же RabbitMQ-очередь. Обработчик выполняется **push-моделью**: брокер сам доставляет сообщение подписанному consumer'у, не воркер "запрашивает" (`ActivateJobs`-poll в Zeebe).
6. Результат отправляется обратно через `COMPLETE_QUEUE` (`ServiceTaskListener.on(ServiceTaskCompleteData data)`, строки 51-60) и публикуется как внутреннее Spring-событие `ServiceTaskCompleted`.

### Сопоставление с Zeebe gRPC Gateway

Согласно `gateway.proto` (Zeebe Gateway service — не удалось зафетчить raw-файл напрямую по одному URL, но получилось получить его содержимое через WebFetch-прокси; список RPC ниже — с высокой уверенностью, но не проверен построчно против самого файла в репозитории camunda/camunda):

- ~24 RPC: `ActivateJobs`, `StreamActivatedJobs`, `CompleteJob`, `FailJob`, `ThrowError`, `PublishMessage`, `DeployResource`, `CreateProcessInstance[WithResult]`, `CancelProcessInstance`, `ResolveIncident`, `SetVariables`, `UpdateJobRetries`, `UpdateJobTimeout`, `UpdateJobPriority`, `ModifyProcessInstance`, `MigrateProcessInstance`, `EvaluateDecision`, `BroadcastSignal`, `Topology`, и др.
- `ActivateJobs` — **pull/poll модель**: воркер сам инициирует gRPC-запрос с `type`, `worker`, `timeout`, `maxJobsToActivate`, `requestTimeout` (long-polling), gateway "round-robin"-ит партиции и возвращает `ActivatedJob[]`, каждый job содержит `variables`/`customHeaders` как **сериализованные JSON-строки**.
- `CompleteJob`/`FailJob`/`ThrowError` — воркер сам явно завершает job по его `key` (int64), с опциональным lease token.

| Аспект | Zeebe (Camunda 8) | ZorroBPM |
|---|---|---|
| Модель доставки | Pull: воркер сам поллит через `ActivateJobs` (или подписывается через `StreamActivatedJobs`) | Push: брокер (RabbitMQ) сам доставляет в очередь, названную по job-типу |
| Транспорт | gRPC (protobuf, `gateway.proto`) | AMQP 0-9-1 (RabbitMQ) |
| Идентификация задачи | `key` (int64), `leaseToken` (опционально) | `serviceTaskId` (UUID) в `JobDetailModel` |
| Формат переменных | JSON-строка (единый документ) в `ActivatedJob.variables` | `Map<String, ProcessVariable>` (типизированные значения: `name`,`value`,`type`) в `JobDetailModel.setVariables` (`ServiceTaskEnqueueServiceImpl.java:69-76`) |
| Явный API для fail/retry/error | `FailJob`(retries+backoff), `ThrowError`(business error) | Есть REST-эквивалент `failServiceTask` (`RuntimeContract.java:24-25`, `POST /service-tasks/{id}/fail`), но это REST, не тот же канал, что доставляет задачу (RabbitMQ) |
| Клиентский SDK | Официальные Zeebe Java/Go/Node/Python клиенты — жёстко используют gRPC-стаб, сгенерированный из `gateway.proto` | Наш клиент — Spring Boot starter (`JobHandler`), Java-only, требует прямого подключения к тому же RabbitMQ broker (тот же логический "namespace") |

### Явный вывод

**Совместимость БЕЗ переписывания Zeebe client SDK на стороне клиента невозможна.** Официальные Zeebe SDK (Java/Go/Node/Python) реализуют gRPC-стаб из `gateway.proto` — они физически не умеют говорить ни REST, ни AMQP; чтобы такой клиент "просто заработал" против ZorroBPM, нужен настоящий gRPC-сервер, отвечающий на тот же protobuf-контракт (минимум `ActivateJobs`, `CompleteJob`, `FailJob`, `ThrowError`, желательно `StreamActivatedJobs`, `PublishMessage`, `DeployResource`, `CreateProcessInstance`, `Topology` — иначе клиентские SDK будут падать на topology-check при коннекте).

Единственный путь: поднять gRPC-gateway поверх движка, который:
1. Реализует `ActivateJobs` как pull-запрос — потребует **новой очереди/выборки задач по запросу** параллельно существующему push-через-RabbitMQ пути (либо переписать доставку задач полностью на pull-модель с lease/visibility-timeout, как в Zeebe, либо держать оба пути одновременно — усложнение).
2. Сериализует variables в единый JSON-документ вместо текущей типизированной Map (несложно — маппинг).
3. Реализует lease/timeout-семантику активированных задач (job "занят" воркером N секунд, при неответе — возвращается в пул) — этого механизма в текущем движке, судя по прочитанному коду, нет вообще (push в очередь = fire-and-forget до ack от consumer'а RabbitMQ, не lease-based).
4. Реализует long-polling (`requestTimeout` в `ActivateJobsRequest`) на gRPC streaming.

Это не адаптер поверх существующего REST-слоя — это **новый компонент** (gRPC server + новая модель distribution задач + protobuf schema, поддерживаемая в соответствии с версией `gateway.proto` Camunda хочет поддерживать). Оценка: месяцы (полноценный gRPC-gateway с pull/lease-семантикой — это по объёму сравнимо с переписыванием ядра диспетчеризации задач движка), не недели. HTTP-обёртка НЕ даст совместимости с существующими Zeebe client SDK без их патча — единственный путь для клиентов остаться "как есть" это gRPC.

---

## 4. Ось 3: BPMN/DMN модель

### BPMN — покрытие элементов

`BpmnElementType` enum (`zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/bpmn/model/BpmnElementType.java`) содержит 43 значения:

| Категория | У нас (BpmnElementType) | У Camunda 8 (Zeebe BPMN, известный охват) |
|---|---|---|
| Tasks | SERVICE_TASK, SEND_TASK, RECEIVE_TASK, SCRIPT_TASK, BUSINESS_RULE_TASK, USER_TASK | Аналогично — service/user/script/business-rule/send/receive task поддержаны в Zeebe |
| Gateways | EXCLUSIVE_GATEWAY, PARALLEL_GATEWAY, EVENT_BASED_GATEWAY, INCLUSIVE_GATEWAY | Тот же набор в Zeebe (exclusive/parallel/inclusive/event-based) |
| Events (start) | START_EVENT, MESSAGE_START_EVENT, TIMER_START_EVENT, SIGNAL_START_EVENT | Совпадает |
| Events (intermediate) | MESSAGE_CATCH_EVENT, TIMER_CATCH_EVENT, SIGNAL_CATCH_EVENT, LINK_CATCH_EVENT, LINK_THROW_EVENT, CONDITIONAL_CATCH_EVENT, MESSAGE_THROW_EVENT, SIGNAL_THROW_EVENT, INTERMEDIATE_CATCH_EVENT, INTERMEDIATE_THROW_EVENT | В целом совпадает (Zeebe тоже поддерживает link/message/timer/signal catch, escalation в некоторых версиях) |
| Events (end) | END_EVENT, ERROR_END_EVENT, ESCALATION_END_EVENT, CANCEL_END_EVENT, TERMINATE_END_EVENT | Совпадает |
| Boundary events | BOUNDARY_TIMER_EVENT, MESSAGE_BOUNDARY_EVENT, SIGNAL_BOUNDARY_EVENT, ERROR_BOUNDARY_EVENT, ESCALATION_BOUNDARY_EVENT, CONDITIONAL_BOUNDARY_EVENT, COMPENSATION_BOUNDARY_EVENT, CANCEL_BOUNDARY_EVENT | Zeebe не поддерживает CANCEL_BOUNDARY_EVENT/CANCEL_END_EVENT (те — BPMN transaction-элементы, устаревшие/непопулярные даже в Camunda 7) — у нас, наоборот, шире в этой части |
| Compensation | COMPENSATION_BOUNDARY_EVENT, COMPENSATION_THROW_EVENT | Zeebe: ограниченная поддержка compensation (сверить точно не удалось без доступа к текущей матрице поддержки Zeebe) |
| Subprocess | SUB_PROCESS, EVENT_SUB_PROCESS, CALL_ACTIVITY | Совпадает |

**Не удалось независимо перепроверить актуальную полную матрицу поддержки BPMN-элементов в Zeebe** (официальная страница "BPMN coverage" не фетчилась в этом исследовании) — таблица выше основана на общеизвестной архитектуре Zeebe, не на построчной сверке официального списка.

### zeebe: namespace extensions — уже реализовано в парсере

`ExtensionElements.java` (`zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/bpmn/xml/ExtensionElements.java:22-41`) явно парсит namespace `http://camunda.org/schema/zeebe/1.0` — это **тот же namespace**, что Camunda 8 Modeler пишет в `.bpmn`-файлы:

- `zeebe:taskDefinition` → `TaskDefinitionModel` (`type`, `retries`) — `.../bpmn/xml/extension/TaskDefinitionModel.java`
- `zeebe:assignmentDefinition` → `AssignmentDefinitionModel` (`assignee`, `candidateGroups`, `candidateUsers`) — `.../extension/AssignmentDefinitionModel.java`
- `zeebe:formDefinition` → `FormDefinitionModel`
- `zeebe:ioMapping` (input/output) → `IoMappingModel` — `.../extension/IoMappingModel.java:21-25`
- `zeebe:calledElement`, `zeebe:calledDecision`, `zeebe:subscription`, `zeebe:script`, `zeebe:loopCharacteristics` (`ZeebeLoopCharacteristicsModel`, `ZeebeScriptModel`) — все присутствуют

Это означает: значительная часть реальных `.bpmn`-файлов, экспортированных из Camunda 8 Modeler (service tasks с job type/retries, user tasks с assignee/candidate groups, input/output mappings), **уже парсится** ZorroBPM без изменений XML-схемы парсера. Не проверено (нужен ручной прогон реального файла из Camunda Modeler через `BpmnParseServiceImpl`, что выходит за рамки чисто аналитического read-only исследования): полное покрытие всех атрибутов, поведение при незнакомых элементах (падает / игнорирует), совпадение семантики retries/multi-instance между движками.

### FEEL

`DmnServiceImpl.java` (`zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/service/impl/DmnServiceImpl.java:1-45`) — комментарий в самом классе: *"built directly on the project's FEEL engine (the same feel-scala line Camunda 8 uses)"*, использует `org.camunda.feel.api.FeelEngineApi` из зависимости `org.camunda.feel:feel-engine` (`zorrobpm-engine/pom.xml`) — **это буквально та же библиотека**, что использует Zeebe/Camunda 8 для FEEL-выражений и unary tests. Условные выражения на sequence flow (`BpmnConditionExpressionModel.java`, использует `xsi:type` атрибут) — необходимо было бы дополнительно проверить, что рантайм-вычисление condition-выражений в движке (не только DMN) вызывает тот же `feelEngineApi`, а не отдельный движок — в `ElementSupport.java`/`ActivityServiceImpl.java` есть упоминания feel/expression, но точный путь вычисления conditions не был прочитан построчно в этом заходе.

### DMN

Модуль есть (`zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/dmn/`). `DmnDefinitionsModel.java` явно комментирует: *"Root `<definitions>` of a DMN 1.3 resource (the namespace Camunda 8 Modeler exports)"*, namespace = `https://www.omg.org/spec/DMN/20191111/MODEL/` (`DmnDefinitionsModel.java:12,18`) — тот же namespace, что и у настоящих DMN 1.3 файлов из Camunda Modeler.

| DMN-фича | Camunda 8 (DMN engine) | ZorroBPM (`DmnServiceImpl.java`) |
|---|---|---|
| Decision table | Да | Да — все hit policy: UNIQUE, FIRST, ANY (default), COLLECT (+ SUM/MIN/MAX/COUNT aggregation), RULE ORDER, OUTPUT ORDER, PRIORITY (`DmnServiceImpl.java:113-121`) |
| Literal expression decision (`<decision><literalExpression>` без таблицы) | Да | **Нет** — `evaluate()` явно требует `decisionTable != null`, иначе `EngineException("...has no decision table")` (`DmnServiceImpl.java:84-86`) |
| Decision requirements graph (decision-to-decision `<informationRequirement>`) | Да | Не найдено в прочитанной модели (`DmnDecisionModel` не содержит requirement-ссылок) — не проверялось глубже |
| Input/output expression, unary tests | FEEL | FEEL (тот же `feelEngineApi`) |
| Типизация переменных при передаче в DMN | JSON | Явный маппинг `ProcessVariableType` → Java-типы перед передачей в FEEL (`toVariableMap`, `DmnServiceImpl.java`) — LONG→Long, BOOLEAN→Boolean, DOUBLE→BigDecimal, JSON→Map/List, иначе String |

### Итог по Оси 3
Это единственная ось, где фундамент уже почти совпадает: тот же XML namespace для zeebe:-расширений, тот же namespace для DMN 1.3, та же FEEL-библиотека. Пробелы конкретны и малы (literal expression decisions, возможно часть event-типов, надо проверить реальный XML-файл из Camunda Modeler на предмет незадокументированных элементов) — это недели, не месяцы.

---

## 5. Итоговая рекомендация

Факты, релевантные для решения:

- **Ось 3 (BPMN/DMN)** — низкая стоимость, потому что фундамент архитектурно уже close: тот же `zeebe:` namespace, тот же DMN 1.3 namespace, буквально та же FEEL-библиотека. Основной оставшийся объём — не переписывание, а закрытие частных пробелов (literal expression decisions, полная сверка списка XML-атрибутов, тестирование на реальных файлах из Camunda Modeler) + явная документация поддерживаемого подмножества. Это даёт непосредственную пользу продукту: пользователь может взять существующую модель процесса, нарисованную в Camunda Modeler для Camunda 8, и с высокой вероятностью развернуть её в ZorroBPM почти без правок.
- **Ось 1 (REST API)** — средняя стоимость за "похожий" API (недели), но экономически несостоятельна за полный byte-compatible контракт (месяцы, ради гипотетических клиентов Camunda 8 REST SDK, которых на рынке существенно меньше, чем инструментов, работающих с диаграммами/моделером).
- **Ось 2 (Job Worker протокол)** — самая дорогая: реальная совместимость с существующими Zeebe-клиентами требует нового gRPC-gateway с pull/lease-семантикой, архитектурно чужеродной для текущей push-через-RabbitMQ модели диспетчеризации задач движка. Это месяцы, затрагивающие ядро движка (`ServiceTaskEnqueueService`, весь путь доставки задач), не периферийный адаптер.

**Рекомендация**: сфокусировать усилия на Оси 3 (BPMN/DMN model-level совместимость) как единственной оси, где "недели работы → реальная ценность для пользователя" — это прямой путь миграции моделей процессов (главный актив, который пользователи Camunda 8 не хотят перерисовывать) без необходимости трогать клиентские SDK или REST-контракт. Ось 1 стоит развивать органически (наш собственный REST API, вдохновлённый ресурсной моделью C8, без цели точного протокольного соответствия) — не как "миграционный путь", а как разумный дизайн. Ось 2 — не начинать, если только не появится явный коммерческий сигнал (конкретные клиенты, которые заявляют "у нас куча существующих Zeebe job worker'ов, которые мы не готовы переписывать") — оправдывающий месяцы инвестиций в gRPC-gateway; в противном случае усилие лучше вложить в собственный, документированный push-based worker SDK (расширение уже существующего `zorrobpm-job-handler-spring-boot-starter`) для не-Java языков (Node/Python/Go клиенты к тому же RabbitMQ-протоколу) — дешевле, чем полная gRPC-эмуляция, и решает ту же практическую проблему "воркер на другом языке".

Решение по объёму работ и приоритету — за CTO/продукт; выше приведены факты и оценки трудозатрат по каждой оси.
