# BPMN Conformance Inventory — WO-OPS-28, ФАЗА 0 (read-only)

- **Дата:** 2026-10-21
- **Ветка:** `fix/ops-28-phase0` от локального master `87bdda7e4` (Merge WO-AUDIT-17)
- **Режим:** read-only анализ кода. Правки в `src/main`, `src/test`, фикстуры, `ci/` и `.gitlab-ci.yml`
  **НЕ вносились**. Изменены только: этот файл, отчёт `docs/internal` и
  запись в канале `docs/internal` (оба — governance-файлы, продуктовый код не тронут).
- **Метод:** факт с диска (V1). Источники: `BpmnSupportScanner.java`, `BpmnParseServiceImpl.java`,
  `BpmnProcessDefinitionModel.java`/`BpmnDefinitionsModel.java` (JAXB-биндинг),
  `FeelSyntaxValidator.java`, `ProcessDefinitionServiceImpl.java` (деплой-гейт), `HandlerRegistry.java`,
  `ActivityServiceImpl.java`, `handler/*Handler.java`, `BoundaryScheduler.java`, `EventTrigger.java`,
  `ErrorEscalationThrower.java`, `MultiInstanceExecutor.java`, фикстуры
  `zorrobpm-engine/src/test/files/**/*.bpmn` (384 файла) + `zorrobpm-rest/src/test/files/*.bpmn` (23),
  711 тест-классов engine+rest (211 engine + 159 rest с `@SpringBootTest`).
- **Предыдущая редакция:** `44421ad79` (2026-10-14, «Qwen», спот-чек maintainer `cbbda4ac2`) — этот документ
  ПЕРЕПИСАН на текущий master; дельта вынесена в §0, старые строки воспроизведены заново и проверены
  по диску (что из старой редакции не перенесено — в §0, D-13).

**Как читать колонку «G-P» (инвариант утечек, правило G-P в `CONTRIBUTING.md`).**

| Значение | Что означает |
|---|---|
| **авто** | элемент покрыт глобальной автопроверкой инварианта утечек (WO-OPS-26, §7): для инстанса, завершённого после baseline, нет activity в `CREATED`/`IN_PROGRESS`, нет висящих `user_tasks`/`service_tasks`, нет несработавших `timer_jobs` на живой activity, нет непотреблённых `message`/`signal`-подписок, нет остаточных arrival-строк `parallel_gateways`, нет scope-переменных в отменённом инстансе. Режим по умолчанию — `report` (§7.3), т.е. проверка ВЫПОЛНЯЕТСЯ, но тест не падает. |
| **свой** | кроме «авто» у теста есть СВОЙ ассерт на **отсутствие** остатка именно по списку G-P (живая activity / task-строка / таймер / подписка / arrival-строка / scope-переменная / `_mi_batch_*` / pending-listener). Приведён `file:line`. Это единственный вид проверки, который даёт «зелёный/красный» в отчёте. |
| **инцидент** | в тесте есть ассерт «нет открытых инцидентов» — родственный мусор-маркер (парковка потока), но **не** часть инварианта G-P: чекер инциденты не проверяет (§7.2, L-5). |
| **нет** | ни своего ассерта на остаток, ни проверки инцидентов. |
| **н/д** | элемент не исполняется (деплой-отказ) — инвариант неприменим. |

Положительный ассерт («активность ЕСТЬ `CREATED`») — **не** проверка остатка и в «свой» не попадает
(§9.2).

---

## 0. Дельта против редакции 2026-10-14 (`44421ad79`)

За неделю master ушёл на 410 коммитов (55 — engine `src/main`, 85 — engine `src/test`). Что изменилось
**по существу для инвентаря** (т.е. меняет состав принимаемого/отвергаемого или проверяемость):

| # | Изменение | Где | Следствие для кампании |
|---|---|---|---|
| D-1 | **Новый код деплой-отказа** `UNSUPPORTED_MULTI_INSTANCE_SHAPE`: MI-маркер внутри `<bpmn:extensionElements>` хоста, который парсер не биндит (всё, кроме `serviceTask`/`userTask`/`subProcess`) | `BpmnSupportScanner.java:244-254` (список хостов `:85-86`), код `:743` | §1: +1 отказ. Покрыт `MiNestedCompletionConditionIntegrationTests.nestedMiOnTransaction_deployRefusedWithShapeCode` (`:144`) |
| D-2 | **Новый деплой-гейт FEEL**: `INVALID_FEEL_EXPRESSION` (400) — тот же FEEL-парсер, что и в рантайме, спрашивает «принимает ли он это выражение?» на распарсенной модели | `FeelSyntaxValidator.java:147-191` (обход полей), гейт `ProcessDefinitionServiceImpl.java:173-194` | §5 — новая секция: перечень полей, которые валидируются/не валидируются на деплое. Покрыт `FeelDeployValidationIntegrationTests` (5 тестов) |
| D-3 | **ioMapping output source без «=»** — отказ на деплое (статическое значение там не выражение, live 8.5) | `FeelSyntaxValidator.java:240-260` | §3 (ioMapping) |
| D-4 | **MI `inputCollection`/`outputElement` без «=»** — отказ на деплое; `inputElement`/`outputCollection` — имена, не выражения, не валидируются | `FeelSyntaxValidator.java:262-293`, флаг исходного написания `BpmnParseServiceImpl.java:2223,:2227` | §3 (MI). Покрыт `MiExpressionFieldDeployValidationIntegrationTests` (5 тестов) |
| D-5 | **MI на embedded `subProcess`** — поддерживается (WO-C8-40): MI-вход, агрегация, join в `MultiInstanceExecutor` | `BpmnSupportScanner.java:61-62` (список НЕ-поддерживаемых контейнеров — `transaction`/`adHocSubProcess`/`callActivity`), `BpmnParseServiceImpl.java:355` | §2 (subProcess + MI) — фикстуры `test-c840-*.bpmn`, тесты `MiSubProcess*` |
| D-6 | **Глобальный инвариант утечек (G-P)** — авто-проверка для КАЖДОГО engine-теста с Spring-контекстом (JUnit-autodetection + `spring.factories`) | `zorrobpm-engine/src/test/java/.../leak/LeakInvariantChecker.java`, `LeakInvariantExtension.java` | §7 — новая секция; меняет смысл колонки «G-P» с «свой ассерт» на «авто + свой» |
| D-7 | **Нормализация `incoming`/`outgoing` из графа `sequenceFlow`** — авто-модель больше не зависит от того, объявил ли автор `<bpmn:incoming>/<bpmn:outgoing>` | `BpmnParseServiceImpl.java:424-428`, `:478-538` | §2/§3 (потоки) — новое поведение, ранее не инвентаризировалось |
| D-8 | **MI `outputElement` — instance-local**, ordered output mappings; ioMapping null-семантика как в Zeebe 8.5 | `MultiInstanceExecutor`, `ElementSupport` | §3 |
| D-9 | **`completionCondition` MI-задачи терминирует сиблингов** | `MiTaskCompletionConditionSiblingsIntegrationTests` | §2/§3 (MI) |
| D-10 | **error-end паркуется в `ERROR`** (не завершает ветку молча), каскад отмены дочерних call-activity | `ErrorEscalationThrower`, `Audit12CancelCascadePgIT` | §2 (endEvent + error) |
| D-11 | Старый документ не содержал `INVALID_HISTORY_TTL` (WO-ENG-17) — деплой-отказ на мусорный `camunda:historyTimeToLive` | `ProcessDefinitionServiceImpl.java:282-304` | §1: +1 отказ |
| D-12 | Старый документ не содержал §5 (FEEL на деплое) и не различал «свой ассерт» и «авто-проверка» | — | §5, §7 |
| D-13 | **Из старой редакции не перенесена строка «`boundaryEvent` на MI-контейнере»** (там — стр. 104, runtime `EventTrigger.fireBoundary`, тесты `MiBoundaryTimerPgIT`/`MiSubProcessIntegrationTests`). Это ровно то расхождение «на теле MI vs per-instance», которое WO-OPS-28 выносит в Фазу 4 и которое maintainer зафиксировал как «расхождение ENG-3» | — | Строка восстановлена в §2 (последняя строка) и в §8 (P-17) |

**Семантические изменения движка после 2026-10-14, которые НЕ меняют состав принимаемого/отвергаемого**
(поэтому в таблицах §1–§3 они не видны, но обязаны быть известны Фазам 1–5): контейнер MI завершается
сам в момент своего end event (WO-C8-43), условия внутри MI-инстанса/подпроцесса видят переменные
скоупа (WO-C8-45), единая точка финализации инстанса и зачистка скоупа (WO-C8-46/47/48/49), «нормализация»
incoming/outgoing (WO-C8-44, см. D-7), ошибка на деплое из-за FEEL (WO-C8-56).

---

## 1. ОТВЕРГАЕТСЯ при деплое (явный отказ, а не тихая подмена)

### 1.1 Сканер неподдерживаемых конструкций (`BpmnSupportScanner`)

Механика: `BpmnParseServiceImpl.parse` вызывает сканер (`:436`) и **записывает** находки в модель
(`pd.setUnsupportedConstructs`), НЕ бросая — старые задеплоенные модели продолжают грузиться.
Отказ живёт в единственной точке — `ProcessDefinitionServiceImpl.addProcessDefinition` (`:143-162`):
HTTP 400, в теле — **первая** находка (код + `elementIds`), полный список — в WARN-лог (`:150-156`).
Отказ происходит **до** транзакции → ни строки версии, ни файла, ни подписки/джоба не создаётся.

| # | Конструкция | Где найдена | Код ответа | Тесты |
|---|---|---|---|---|
| 1 | Несколько `<process>` в одном ресурсе | `BpmnSupportScanner.java:147-154` | `MULTIPLE_PROCESSES_IN_RESOURCE` (`:718`) | `UnsupportedConstructDeployIntegrationTests.criterion3_severalProcessesInOneResource_rejectedNamingBoth` (`:117`) |
| 2 | `<complexGateway>` | `:160-167` | `UNSUPPORTED_COMPLEX_GATEWAY` (`:719`) | `…criterion2_complexGateway_rejectedWithItsElementId` (`:99`) |
| 3 | `standardLoopCharacteristics` — **прямое написание** | `:174-182` | `UNSUPPORTED_STANDARD_LOOP` (`:720`) | `…criterion1_standardLoopCharacteristics_rejectedAndNothingPersisted` (`:67`); `LoopCharacteristicsSpellingDeployTests.criterion1_standardLoopWrittenDirectly_rejectedOnEveryHostKind` (`:133`) |
| 3a | `standardLoopCharacteristics` — **записанный как абстрактный head-элемент** `<loopCharacteristics xsi:type="tStandardLoopCharacteristics">` | тот же `:174-182` (тип резолвится через namespace — `:441-463`) | `UNSUPPORTED_STANDARD_LOOP` (`:720`) | `LoopCharacteristicsSpellingDeployTests.criterion1_standardLoopWrittenAsAbstractHeadElement_rejectedOnEveryHostKind` (`:85`; ассертит код `UNSUPPORTED_STANDARD_LOOP` — `:94`), `xsiTypeIsResolvedThroughTheNamespace_notThePrefixText` (`:202`; ассертит `UNSUPPORTED_STANDARD_LOOP` — `:228,:253`) |
| 4 | MI на `transaction` / `adHocSubProcess` / `callActivity` | `:188-198` (список `:61-62`) | `UNSUPPORTED_MULTI_INSTANCE_CONTAINER` (`:721`) | `…criterion5_multiInstanceOnContainer_rejected` (`:225`); `MultiInstanceOnContainersParseTest.multiInstanceOnCallActivity_isRefusedAtDeployNamingIt` (`:58`) |
| 5 | MI на `task`/`scriptTask`/`manualTask`/`businessRuleTask`/`sendTask`/`receiveTask` | `:203-213` (список `:74-75`) | `UNSUPPORTED_MULTI_INSTANCE_TASK` (`:728`) | `LoopCharacteristicsSpellingDeployTests.multiInstanceWrittenDirectly_refusedOnContainersAndTasks_acceptedOnSupportedKinds` (`:153`) |
| 6 | MI, записанное как `<loopCharacteristics xsi:type="tMultiInstanceLoopCharacteristics">` (head-элемент схемы) | `:220-231` | `UNSUPPORTED_MULTI_INSTANCE_XSI_TYPE` (`:734`) | `LoopCharacteristicsSpellingDeployTests.multiInstanceWrittenAsAbstractHeadElement_rejectedOnEveryHostKind` (`:113`; код MI — `:118`), `theTwoMultiInstanceRefusals_carryDifferentCodes` (`:338`; код — `:345`) |
| 7 | MI внутри `<bpmn:extensionElements>` хоста, который парсер не биндит | `:244-254` (список хостов `:85-86`) | `UNSUPPORTED_MULTI_INSTANCE_SHAPE` (`:743`) | `MiNestedCompletionConditionIntegrationTests.nestedMiOnTransaction_deployRefusedWithShapeCode` (`:144`) |
| 8 | Conditional start event | `:257-265` | `UNSUPPORTED_CONDITIONAL_START_EVENT` (`:744`) | `…criterion5_conditionalStartEvent_rejectedNotSilentlyMadeAPlainStart` (`:207`) |
| 9 | `sequenceFlow` с `sourceRef`/`targetRef` на несуществующий flow node | `:269-276` (резолв — по **документу**, не по модели парсера: `:564-593`) | `UNRESOLVED_SEQUENCE_FLOW_REF` (`:745`) | `…criterion4_unresolvableFlowRef_rejectedNamingTheFlow` (`:138`), `criterion4_danglingRefInsideASubProcess_isFoundToo` (`:178`), `criterion4_refToANodeOfTheParentScopeInsideASubProcess_isNotDangling` (`:192`), `criterion6_refsToNodesTheParserDoesNotModel_areNotReportedAsDangling` (`:155`) |

Стабильный порядок кодов — `BpmnSupportScanner.CODES` (`:705-714`); контракт ответа совпадает с
уже установленным `SERVICE_TASK_MISSING_JOB` — `…criterion7_refusalUsesTheSameShapeAsServiceTaskMissingJob` (`:348`),
`…criterion7_messageNamesTheConstructAndTheOffendingElement` (`:362`).
REST-контур: `UnsupportedConstructDeployRejectionIT.unsupportedConstruct_isRejected400_withStableCodeAndElementIds` (`:79`).

### 1.2 Отказы вне сканера

| # | Конструкция | Где | Код ответа | Тесты |
|---|---|---|---|---|
| 10 | Service task без `zeebe:taskDefinition` | `ProcessDefinitionServiceImpl.java:113-131` | `SERVICE_TASK_MISSING_JOB` (400) | `ServiceTaskJobValidationIntegrationTests` |
| 11 | BPMN-файл > 5 МБ | `ProcessDefinitionServiceImpl.java:94-102` | `BPMN_TOO_LARGE` (413) | `BatchDeployValidationIT` |
| 12 | Нечитаемый/неположительный `camunda:historyTimeToLive` | `:282-304` | `INVALID_HISTORY_TTL` (400) | WO-ENG-17 |
| 13 | FEEL, который парсер движка не принимает | `:173-194` + `FeelSyntaxValidator.java` | `INVALID_FEEL_EXPRESSION` (400) | §5 |
| 14 | Нет ни одного start event | `BpmnParseServiceImpl.checkBpmn:1738-1740` | `BpmnParseException` → HTTP 400 `PARSE_ERROR` (путь `POST /process-definitions`; текст — только в лог) | `BpmnParseServiceImplTest` |
| 15 | Больше одного «простого» (none) start event | `:1741-1755` | `BpmnParseException` → 400 `PARSE_ERROR` (путь `POST /process-definitions`) | `BpmnParseServiceImplTest` |
| 16 | Нет ни одного end event | `:1756-1758` | `BpmnParseException` → 400 `PARSE_ERROR` (путь `POST /process-definitions`) | `BpmnParseServiceImplTest` |
| 17 | Ad-hoc subProcess со start event внутри | `BpmnParseServiceImpl.java:1374-1376` | `BpmnParseException` → 400 `PARSE_ERROR` (путь `POST /process-definitions`) | `BpmnParseServiceImplTest` |
| 18 | **Любая** ошибка парсинга (битый XML, невалидная схема, не-XML) — catch-all | `BpmnParseServiceImpl.java:439-440` (`catch (Exception e) → BpmnParseException`) | 400 `PARSE_ERROR` на пути `POST /process-definitions`; на пути новой версии — 400 **без кода** («BPMN could not be parsed», `ProcessDefinitionResource.java:121-126`) | `BpmnParseServiceImplTest`, `ProcessDefinitionResource` IT |
| 19 | Ключ процесса внутри BPMN не совпадает с ключом целевого определения (путь новой версии) | `ProcessDefinitionResource.java:129-137` | `PROCESS_KEY_MISMATCH` (400) | IT пути новой версии |
| 20 | Батч-деплой: любой `EngineException` в пачке | `DeploymentResource.java:52-54` | 400 «Deployment failed» (без кода) | IT батч-деплоя |

**Оговорка о коде ответа.** `PARSE_ERROR` — контракт **пути `POST /process-definitions`** (`GlobalExceptionHandler.java:151-161`).
Путь новой версии (`PUT` версии) ловит тот же `BpmnParseException` сам и отвечает `ResponseStatusException(BAD_REQUEST, …)`
**без кода** (`ProcessDefinitionResource.java:121-126`) — чтобы не выдавать детали парсера (WO-SEC-33).

---

## 2. ПРИНИМАЕТСЯ: элементы (flow nodes)

Путь: JAXB-биндинг (`BpmnProcessDefinitionModel.java:15-88` — это **полный** список того, что вообще
парсится; всё, чего там нет, — «тихо игнорируется», §4) → `BpmnParseServiceImpl.parse` (`:70-442`) →
`BpmnElementModel` → runtime-диспатч `ActivityServiceImpl.execute` (`:123-173`).
Алиасы типов — `HandlerRegistry.java:60-66`: `MESSAGE/TIMER/SIGNAL_START_EVENT → START_EVENT`,
`LINK_CATCH_EVENT → START_EVENT`, `RECEIVE_TASK → MESSAGE_CATCH_EVENT`.
Элемент без handler'а → **инцидент** «Unsupported BPMN element type» (`ActivityServiceImpl.java:148-157`),
а не тихий дроп.

| Элемент | Парсинг (file:line) | Runtime (file:line) | Основные тесты | G-P |
|---|---|---|---|---|
| `startEvent` (none) | `BpmnParseServiceImpl.java:138-193`, тип `:2277-2279` | `StartThrowEventHandler.StartEvent` (`:29`) | `Process1IntegrationTests`…`Process8IntegrationTests`, `BpmnStructureCoverageTest`, `BpmnStructureIntegrationTests` | **авто** |
| `startEvent` + `message` | `:143-151`, тип `:2271-2272` | alias → `StartThrowEventHandler`; подписка `DeploymentArtifactRegistrar.registerMessageStartSubscriptions` | `MessageStartIntegrationTests`, `MessageSubscriptionFanOutIntegrationTests`, `MessageCorrelationKeyIntegrationTests` | **авто** |
| `startEvent` + `timer` (date/duration/cycle) | `:152-168`, тип `:2273-2274` | alias → `StartThrowEventHandler`; `registerTimerStartJobs` | `TimerStartIntegrationTests`, `TimerStartCycleIntegrationTests`, `RepeatingTimerSemanticsPgIT` | **авто** + **свой** (`RepeatingTimerSemanticsPgIT:147,:197` — «no 4th re-arm», `pendingAfter` пуст) |
| `startEvent` + `signal` | тип `:2275-2276` | alias → `StartThrowEventHandler`; `registerSignalStartSubscriptions` | `SignalStartBoundaryIntegrationTests` | **авто** |
| `startEvent` + `conditional` | **отвергается** (§1.1, #8) | — | — | н/д |
| `endEvent` (none) | `:195-201`, тип `:2247-2248` | `EndEventHandler.EndEvent` (`:36`) | `NoneEndEventPgIT` (проверяет «нет досрочного завершения», `:150-157`), `Process1–8IntegrationTests` | **авто** (своего ассерта на остаток в классе нет) |
| `endEvent` + `terminate` | тип `:2239-2240` | `EndEventHandler.TerminateEndEvent` (`:87`) | `TerminateEndIntegrationTests`, `TerminateScopeIntegrationTests` (`:89` — положительный ассерт, что внешняя ветвь выжила), `TerminateSubscopeTeardownIntegrationTests` | **авто** + **свой** (`TerminateSubscopeTeardownIntegrationTests:112-114` — scope-локалы после teardown пусты) |
| `endEvent` + `error` | тип `:2241-2242`, def `:549-552` | `EndEventHandler.ErrorEndEvent` (`:220`) + `ErrorEscalationThrower.throwError` (`:80`) | `Camunda8ParityCharacterizationTests`, `ErrorEscalationThrowerTest`, `Audit11ErrorEndAndForkScopeIntegrationTests` | **авто** + **свой** (`Audit11ErrorEndAndForkScopeIntegrationTests:184` — `activities(pi,"siblingTask")).isEmpty()`) + **инцидент** (`:165`) |
| `endEvent` + `escalation` | тип `:2243-2244`, def `:558-560` | `EndEventHandler.EscalationEndEvent` (`:281`) + `ErrorEscalationThrower.throwEscalation` (`:394`) | `EscalationIntegrationTests` | **авто** |
| `endEvent` + `cancel` | тип `:2245-2246` | `CancelEndHandler` (`:49`) | `ForkCancelInTransactionIntegrationTests`, `Audit12CancelCascadePgIT` | **авто** + **инцидент** (`ForkCancelInTransactionIntegrationTests:122`) |
| `endEvent` + `compensate` | def `:575-581`, **тип остаётся `END_EVENT`** (`:2247-2248`) | **компенсация не исполняется** — см. §4-7 | `Camunda8ParityCharacterizationTests` (характеризация) | **авто** |
| `serviceTask` | `:203-209`, `:1761-1808` | `ServiceTaskHandler` (`:32`) → AMQP-воркер | `ServiceTaskFailureIntegrationTests`, `CompleteServiceTaskPhaseResumePgIT`, `ListenerDuplicateCompletionTests` | **авто** + **свой** (`ListenerDuplicateCompletionTests:107` — pending-listener index = 0) |
| `sendTask` | `:210-216`, `:2028-2050` (job-форма `:2037-2043`, message-форма `:2044-2048`) | `SendTaskHandler` (`:29`) | `SendReceiveTaskIntegrationTests`, `Camunda8ParityCharacterizationTests` | **авто** |
| `receiveTask` | `:217-223`, `:2052-2067` | alias → `MessageCatchHandler` (`:34`) | `SendReceiveTaskIntegrationTests`, `ReceiveTaskBoundaryIntegrationTests` (`:141` — положительный ассерт) | **авто** |
| `manualTask` | `:224-230`, `:2069-2077` | `SyncTaskHandler.ManualTask` (`:101`) | `Camunda8ParityCharacterizationTests`, `BpmnStructureCoverageTest` | **авто** |
| `task` (без типа) | `:231-238`, `:2082-2090` | `SyncTaskHandler.UndefinedTask` (`:138`) | `BpmnStructureCoverageTest` (`test-diff9-untyped-task.bpmn`) | **авто** |
| `scriptTask` | `:239-245`, `:1948-1987` | `SyncTaskHandler.ScriptTask` (`:35`) | `ScriptTaskIntegrationTests`, `Camunda8ParityCharacterizationTests` | **авто** |
| `businessRuleTask` | `:246-252`, `:2005-2026` (`calledDecision` `:2013-2017`, FEEL `:2018-2021`) | `SyncTaskHandler.BusinessRuleTask` (`:166`) | `BusinessRuleTaskIntegrationTests` | **авто** |
| `userTask` | `:253-259`, `:2092-2203` | `UserTaskHandler` (`:31`) | `UserTaskQueryIntegrationTests`, `CompleteUserTaskCharacterizationPgIT`, `Camunda8ParityCharacterizationTests` | **авто** |
| `exclusiveGateway` | `:260-267`, `:2287-2302` (defaultFlow `:2294-2298`) | `ExclusiveGatewayHandler` (`:30`) | `Diff2ExclusiveGatewayOrderTest`, `ExclusiveGatewayUnsupportedShapeIntegrationTests`, `GatewayNoDefaultIntegrationTests`, C8-35-семья | **авто** |
| `parallelGateway` | `:268-275`, `:2304-2314` | `ParallelGatewayHandler` (`:35`) | `ImplicitForkPendingBranchesIntegrationTests`, `ParallelGatewayDegenerateIntegrationTests`, `NoneEndEventPgIT` | **авто** |
| `eventBasedGateway` | `:276-289` | `EventBasedGatewayHandler` (`:26`) | `EventBasedGatewayIntegrationTests`, `TimerMessageQueryIntegrationTests` | **авто** |
| `inclusiveGateway` | `:290-308` (defaultFlow `:298-302`) | `InclusiveGatewayHandler` (`:35`) | `InclusiveGatewayIntegrationTests`, `InclusiveJoinReadinessMatrixIntegrationTests` (матрица), `InclusiveJoinReadinessIntegrationTests`, `InclusiveJoinDeactivationWakeupIntegrationTests` | **авто** + **свой** (`InclusiveJoinDeactivationWakeupIntegrationTests:152` — `countOf(pi,"taskAfter",CREATED)).isEqualTo(0L`) + **инцидент** (`InclusiveJoinReadinessIntegrationTests:199`) |
| `intermediateCatchEvent` + `message` | `:315-323`, тип `:2323-2324` | `MessageCatchHandler` (`:34`) | `Camunda8ParityCharacterizationTests`, `MessageSubscriptionFanOutIntegrationTests`, `ContainerSubscriptionCloseIntegrationTests` | **авто** + **свой** (`ContainerSubscriptionCloseIntegrationTests:111` — подписка потреблена, т.е. непотреблённой подписки не осталось) |
| `intermediateCatchEvent` + `timer` | тип `:2325-2326`, таймер `:2337-2353` | `TimerCatchHandler` (`:35`) | `ActivityServiceImplTests`, `FeelTimerPgIT`, `BoundedTimerIntegrationTest` | **авто** |
| `intermediateCatchEvent` + `signal` | тип `:2327-2328` | `SignalCatchHandler` (`:30`) | `SignalIntegrationTests` | **авто** |
| `intermediateCatchEvent` + `link` | тип `:2329-2330` | alias → `StartThrowEventHandler` | `LinkEventIntegrationTests` | **авто** |
| `intermediateCatchEvent` + `conditional` | тип `:2331-2332`, def `:561-571` | `ConditionalCatchHandler` (`:36`) | `ConditionalEventIntegrationTests` (`:113` и далее — **положительные** ассерты «активность ЕСТЬ `CREATED`») | **авто** |
| `intermediateCatchEvent` (без def) | `:315-323`, тип `:2333-2334` | `WaitStateHandler` (`:26`) | `ActivityServiceImplTests` | **авто** |
| `intermediateThrowEvent` + `message` | `:325-334`, тип `:2378-2379` | `MessageThrowHandler` (`:34`) | `ActivityServiceImplTests` | **авто** |
| `intermediateThrowEvent` + `signal` | тип `:2385-2386` | `SignalThrowHandler` (`:31`) | `SignalIntegrationTests` | **авто** |
| `intermediateThrowEvent` + `escalation` | тип `:2387-2388` | `StartThrowEventHandler.StartEscalationThrow` (`:51`) | `EscalationIntegrationTests` | **авто** |
| `intermediateThrowEvent` + `link` | тип `:2389-2390` | `LinkThrowHandler` (`:31`) | `LinkEventIntegrationTests` | **авто** |
| `intermediateThrowEvent` + `compensate` | тип `:2391-2401` (activityRef `:2394-2401`), `waitForCompletion` `:578-581` | `CompensationThrowHandler` (`:40`) | `CompensationIntegrationTests`, `CompensationWaitIntegrationTests`, `CompensationScopeIntegrationTests` | **авто** (absence-ассертов на остаток в этих классах нет) |
| `intermediateThrowEvent` (без def) | тип `:2402-2403` | `IntermediateThrowEventHandler` (`:27`) | `ActivityServiceImplTests` | **авто** |
| `callActivity` | `:336-342`, `:2426-2450` (calledElement `:2436-2444`, ioMapping `:2447`) | `CallActivityHandler` (`:40`) | `CallActivityVariablePropagationIntegrationTests`, `PropagateChildVariablesIntegrationTests`, `CallActivityNullSafetyIntegrationTests`, `CallActivityRecursionIntegrationTests`, `Audit12CancelCascadePgIT` | **авто** |
| `subProcess` (embedded) | `:344-359`, `toSubProcessElement:651-744` (плоскость `:668-744`, MI `:355`) | `SubProcessHandler` (`:35`) | `SubProcessIntegrationTests`, `NestedTokenPendingBranchesIntegrationTests`, `Diff1IoMappingScopeReproTest` | **авто** |
| `subProcess` + MI | `:355` (`attachSubProcessMultiInstance`), `:793-861` | `MultiInstanceExecutor` | `MiSubProcessIntegrationTests`, `MiSubProcessMinimalPgIT`, `MiSubProcessRaceIntegrationTests`, `MiSubProcessApprovalRouteIntegrationTests`, `MiSubProcessOrphanContainerIntegrationTests` | **авто** + **свой** (`MiSubProcessIntegrationTests:202` — `activities(pi,"utProbe",CREATED)).isEmpty()`; `MiSubProcessOrphanContainerIntegrationTests:151` — `liveActivities(pi)).isZero()`) + **инцидент** (`MiSubProcessIntegrationTests:184`) |
| `subProcess` + MI (Camunda8-parity) | фикстуры `test-c841-parity-*.bpmn` | `MultiInstanceExecutor` | `MiSubProcessCamunda8ParityIntegrationTests` | **авто** + **свой** (`:199` — в родительский scope не утекли `tu`/`item`/`loopCounter`) + **инцидент** (`:162`) |
| `subProcess` + MI (loop-var/reentry) | фикстуры `test-c842-*.bpmn` | `MultiInstanceExecutor` + scope-chain | `MiSubProcessLoopVarVisibilityIntegrationTests` | **авто** + **свой** (`:291` — `rootRows(pi,lane)).isEmpty()`, т.е. scope-строки не остались) + **инцидент** (`:295`) |
| `transaction` | `:363-371` (парсится как `SUB_PROCESS`; cancel-семантика — через cancel-end/cancel-boundary) | `SubProcessHandler` + `CancelEndHandler` | `TransactionCancelIntegrationTests`, `ForkCancelInTransactionIntegrationTests` | **авто** + **инцидент** (`ForkCancelInTransactionIntegrationTests:122`) |
| `eventSubProcess` (interrupting) | `:655-656` (тип по `triggeredByEvent`), `:668-744` | **отдельного handler'а нет** — `EventTrigger.triggerEventSubprocess:418`; error-триггер `ErrorEscalationThrower:186` | `EventSubProcessIntegrationTests`, `NestedEventSubprocessCancelScopeIntegrationTests`, `EventSubProcessInclusiveJoinIntegrationTests` | **авто** + **свой** (`EventSubProcessInclusiveJoinIntegrationTests:162` — `countOf(pi,"taskNotify",CREATED)).isEqualTo(0L`) + **инцидент** (`NestedEventSubprocessCancelScopeIntegrationTests:100`) |
| `eventSubProcess` (non-interrupting) | `:655-656` | `EventTrigger:418` | `EventSubProcessInclusiveJoinIntegrationTests`, C8-35-семья | **авто** + **инцидент** (`EventSubProcessInclusiveJoinIntegrationTests:232`) |
| `adHocSubProcess` | `:375-382`, `toAdHocSubProcessElement:1363-1431` | `AdHocSubProcessHandler` (`:61`) | `AdHocSubProcessIntegrationTests`, `AdHocJobWorkerIntegrationTests`, `AdHocScopeTokenIntegrationTests`, `AdhocScopeArrivalCleanupIntegrationTests`, `AdHocCompleteEndpointIT` | **авто** + **свой** (`AdHocSubProcessIntegrationTests:194`; `AdHocJobWorkerIntegrationTests:134`; `AdHocScopeTokenIntegrationTests:160` — `rows(pi,"afterAdhoc")).isEmpty()`) |
| `boundaryEvent` + `timer` | `:386`, `:394-396` (тихий дроп прочих), `:607-620` | `BoundaryScheduler.scheduleBoundaryTimers:95`; re-arm `EventTrigger:368` | `ServiceTaskBoundaryTimerIntegrationTests/PgIT`, `ContainerBoundaryTimerIntegrationTests`, `SubprocessBoundaryTimerIntegrationTests`, `ReceiveTaskBoundaryIntegrationTests`, `ForkBoundaryScopeIntegrationTests`, `BoundaryCycleIntegrationTests`, `RepeatingBoundaryTimerPgIT`, `ArmedBoundaryTimerJoinPgIT`, `MiBoundaryTimerPgIT` | **авто** + **свой** (`ArmedBoundaryTimerJoinPgIT:122` — `countOf(pi,"taskNotify",CREATED)).isZero()`; `RepeatingBoundaryTimerPgIT:156` — `pendingAfter).isEmpty()`) |
| `boundaryEvent` + `error` | `:621-624` | `ErrorEscalationThrower.findErrorBoundary:254-279` | `ErrorBoundaryIntegrationTests` (`:64-66` — положительные ассерты; `:68` — absence-ассерт `noneMatch(...)` по элементу `endEvent`), `MiSubProcessIntegrationTests` | **авто** |
| `boundaryEvent` + `message` | `:625-627`, def `:400-406` | `BoundaryScheduler.scheduleMessageBoundaries:60` | `MessageBoundaryIntegrationTests` (`:71-72` — положительные; `:73` — absence-ассерт `noneMatch(...)` по `endEvent`), `ContainerSubscriptionCloseIntegrationTests` | **авто** + **свой** (`ContainerSubscriptionCloseIntegrationTests:111`) |
| `boundaryEvent` + `signal` | `:628-630` | `BoundaryScheduler.scheduleSignalBoundaries:31`; broadcast `EventTrigger:711` | `SignalStartBoundaryIntegrationTests`, `SignalFanOutIntegrationTests`, `MiBoundaryTimerPgIT` | **авто** |
| `boundaryEvent` + `escalation` | `:631-634` | `ErrorEscalationThrower.findEscalationBoundary:505-512` | `EscalationIntegrationTests` | **авто** |
| `boundaryEvent` + `conditional` | `:635-638`, def `:561-571` | `EventTrigger:84` | `ConditionalEventIntegrationTests` | **авто** |
| `boundaryEvent` + `compensate` | `:639-642`, association `:412-422`, handler `:2414-2424` | компенсационная механика scope-фильтра | `CompensationIntegrationTests`, `CompensationWaitIntegrationTests`, `CompensationScopeIntegrationTests` | **авто** |
| `boundaryEvent` + `cancel` | `:643-645` | `ErrorEscalationThrower` (cancel-путь) | `ForkCancelInTransactionIntegrationTests` | **авто** + **инцидент** (`ForkCancelInTransactionIntegrationTests:122`) |
| `boundaryEvent` без исполняемого event-definition | `:394-396` — **тихий дроп** | — | §4-1 (тест-стража нет) | **нет** |
| `boundaryEvent` **на MI-контейнере** | `:384-409` (парсинг), граница на MI-хосте | `EventTrigger.fireBoundary` (scope-отмена хоста) | `MiBoundaryTimerPgIT` (`test-mi-boundary.bpmn`, `test-mi-boundary-signal.bpmn`), `MiSubProcessIntegrationTests` | **авто**; **одностороннее место**: семантика «на теле MI vs per-instance» (расхождение ENG-3) не разделена — см. §8 P-17 |

---

## 3. ПРИНИМАЕТСЯ: свойства элементов

| Свойство | Парсинг (file:line) | Runtime-потребитель | Основные тесты | G-P |
|---|---|---|---|---|
| `userTask`: assignee / candidateUsers / candidateGroups | `BpmnParseServiceImpl.java:2107-2111` | `UserTaskHandler` + scope-резолв MI | `UserTaskQueryIntegrationTests`, `MiSubProcessLoopVarVisibilityIntegrationTests` | **авто** + **свой** (`MiSubProcessLoopVarVisibilityIntegrationTests:291`) |
| `userTask`: dueDate / followUpDate | `:2112-2116` | планировщик задач | `UserTaskQueryIntegrationTests` | **авто** |
| `userTask`: priorityDefinition | `:2117-2123` | очередь задач | `Camunda8ParityCharacterizationTests` | **авто** |
| `userTask`: formDefinition (formKey/externalReference/formId/bindingType/versionTag) | `:2124-2147` | форма задачи | `Camunda8ParityCharacterizationTests`, `Camunda8FormsIntegrationTests`, `Api1FormValidatorIT` | н/д (вне runtime) |
| `userTask`: taskListeners (creating/completing/assigning/updating/canceling) | `:2153-2196` | `ElementSupport` | `Camunda8ParityCharacterizationTests`, `CompleteUserTaskCharacterizationPgIT`, `ListenerDuplicateCompletionTests` | **авто** + **свой** (`ListenerDuplicateCompletionTests:107,:365`) |
| `serviceTask`: `zeebe:taskDefinition` (type/retries) | `:1835-1864` (`attachServiceTaskJob`) | `ServiceTaskHandler` | `ServiceTaskFailureIntegrationTests`, `ServiceTaskJobValidationIntegrationTests` | **авто** |
| `serviceTask`: taskHeaders | `:1895-1916` (`attachTaskHeaders`) | `ServiceTaskHandler` | `Camunda8ParityCharacterizationTests` | **авто** |
| `serviceTask`: jobPriorityDefinition | `attachServiceTaskJob` + process-level default `:127-132` | `ServiceTaskHandler` | `Camunda8ParityCharacterizationTests` | **авто** |
| `serviceTask`: executionListeners (start/end) | `:1761-1803` (слушатели `:1780-1802`), общий `:1866-1894` | `ElementSupport` | `Camunda8ParityCharacterizationTests`, `ListenerDuplicateCompletionTests` | **авто** + **свой** (`ListenerDuplicateCompletionTests:107`) |
| MI: isSequential / loopCardinality / completionCondition | `:2208-2234` (`attachMultiInstance`) | `MultiInstanceExecutor` | `MultiInstanceIntegrationTests`, `MultiInstanceCardinalityValidationIntegrationTests`, `MiCompletionConditionPgIT`, `MiFullScanPgIT`, `MiReenterIntegrationTests`, `MiLoopReentryRacePgIT`, `MiTaskCompletionConditionSiblingsIntegrationTests` | **авто** + **свой** (`MultiInstanceCardinalityValidationIntegrationTests:113` — `miTasks(pi,CREATED)).isEmpty()`; `MiLoopReentryRacePgIT:175` — arrival-строки обнулены; `MiTaskCompletionConditionSiblingsIntegrationTests:190-197` — четыре явных LEAK-INVARIANT-ассерта) + **инцидент** (`MiTaskCompletionConditionSiblingsIntegrationTests:197`) |
| MI: `zeebe:loopCharacteristics` (inputCollection/inputElement/outputCollection/outputElement) | `:2221-2231` | `MultiInstanceExecutor` | `MultiInstanceOutputIntegrationTests`, `MiOutputCollectionDiffIntegrationTests`, `MiExpressionFieldDeployValidationIntegrationTests` | **авто** + **свой** (`MiOutputCollectionDiffIntegrationTests:184` — `miTasks(pi)).isEmpty()`; `:167` — `doesNotContain("_mi_batch_miTask")`) |
| MI: `inputCollection`/`outputElement` **без «=»** | — | **отказ на деплое** (§1.2 #13 / D-4) | `MiExpressionFieldDeployValidationIntegrationTests.inputCollectionWithoutEquals_isRejectedAtDeploy` (`:78`), `outputElementWithoutEquals_isRejectedAtDeploy` (`:107`) | н/д |
| MI: `inputElement`/`outputCollection` — имена, не выражения | `:2224,:2225` | `MultiInstanceExecutor` | `MiExpressionFieldDeployValidationIntegrationTests.inputElementAndOutputCollection_areNamesAndAreNotRefused` (`:211`) | н/д |
| timer: timeDate / timeDuration / timeCycle (start/catch/boundary) | start `:157-166`, catch `:2337-2353`, boundary `:607-620` | планировщик таймеров | `TimerStartIntegrationTests`, `ActivityServiceImplTests`, `BoundedTimerIntegrationTest`, `RepeatingBoundaryTimerPgIT`, `RepeatingTimerSemanticsPgIT` | **авто** + **свой** (`RepeatingTimerSemanticsPgIT:147,:197`; `RepeatingBoundaryTimerPgIT:156`) |
| `sequenceFlow`: conditionExpression | `:1727-1732` (`toFlowModel:1723-1736`) | `FlowNavigator` / `InclusiveGatewayHandler.isFlowActive` | `Diff2ExclusiveGatewayOrderTest`, `Diff10GatewayNoConditionNpeTest`, `FeelDeployValidationIntegrationTests` | **авто** |
| `sequenceFlow`: defaultFlow (exclusive/inclusive) | `:2294-2298`, `:298-302` | gateway | `GatewayNoDefaultIntegrationTests`, `Diff2ExclusiveGatewayOrderTest` | **авто** |
| `incoming`/`outgoing` — нормализация из графа | `:424-428`, `:478-538` | `FlowNavigator` и все потребители | `MiSubProcessApprovalRouteIntegrationTests` (WO-C8-44), `ActivityServiceImplTests.recursionDepthGuardStopsInfiniteLoop` | **авто** + **инцидент** (`MiSubProcessApprovalRouteIntegrationTests:252`) |
| `callActivity`: calledElement (processId/bindingType/versionTag/propagateAll*) | `:2436-2444` | `CallActivityHandler` | `CallActivityVariablePropagationIntegrationTests`, `PropagateChildVariablesIntegrationTests` | **авто** |
| `callActivity`: ioMapping | `:2447` (`attachIoMapping`) | `ElementSupport` | `CallActivityVariablePropagationIntegrationTests`, `Diff1IoMappingScopeReproTest` | **авто** |
| ioMapping input/output (общий) | `:1917-1947` (`attachIoMapping`, `toMappings:1936-1947`), вызовы: `:752` (subProcess), `:1804` (serviceTask), `:2199` (userTask), `:2447` (callActivity) | `ElementSupport` | `IoMappingIntegrationTests`, `Eng29FeelNullIoMappingIncidentIntegrationTests`, `Eng30LenientModeNoIncidentIntegrationTests`, `Eng33NowIoMappingIntegrationTests`, `FeelIoMappingConformanceIntegrationTests`, `IoMappingStaticSourceIntegrationTests` | **авто** |
| ioMapping **output** source без «=» | — | **отказ на деплое** (§1.2 #13 / D-3) | `IoMappingStaticSourceIntegrationTests` | н/д |
| ioMapping **input** source без «=» | `FeelSyntaxValidator.java:220-232` | статическое значение, копируется дословно | `IoMappingStaticSourceIntegrationTests` | н/д |
| `<message>`: `zeebe:subscription correlationKey` | `:80-89` (`ExtensionElements::getSubscription` → `messageKeys`) | `:404` (граница верхнего уровня), `:1695` (граница во вложенном скоупе), `:2062` (receive task), `:2362` (intermediate catch) — `msg.setCorrelationKeyExpression(...)` | `MessageCorrelationKeyIntegrationTests` | **авто** |
| adHoc: activeElementsCollection / outputCollection / outputElement | `toAdHocSubProcessElement:1363-1431` | `AdHocSubProcessHandler` | `AdHocSubProcessIntegrationTests`, `AdHocJobWorkerIntegrationTests` | **авто** + **свой** (см. §2, adHoc) |
| adHoc: completionCondition | там же | `AdHocSubProcessHandler` | `AdHocSubProcessIntegrationTests` | **авто** |
| adHoc: cancelRemainingInstances | там же | `AdHocSubProcessHandler` | `AdHocSubProcessIntegrationTests` | **авто** |
| conditional: conditionalFilter (variableNames/variableEvents) | `:564-571` | `ConditionalCatchHandler` / `EventTrigger` | `ConditionalEventIntegrationTests` | **авто** |
| compensate: waitForCompletion | `:578-581` | `CompensationThrowHandler:80` | `CompensationWaitIntegrationTests` | **авто** |
| start event: formDefinition/formKey | `:177-192` | стартовая форма | `BpmnParseStartFormTest`, `Camunda8FormsIntegrationTests` | н/д |
| end/throw: job-based (`zeebe:taskDefinition`) | `:2252`, `:2406` (`attachServiceTaskJob`) | `ActivityServiceImpl.isJobBasedEvent:187-194` | `Camunda8ParityCharacterizationTests` | **авто** |
| script: `zeebe:script` (expression/resultVariable) | `:1960-1975` | `SyncTaskHandler.ScriptTask` | `ScriptTaskIntegrationTests` | **авто** |
| script: inline `<script>` | там же | `SyncTaskHandler.ScriptTask` | `ScriptTaskIntegrationTests` | **авто** |
| businessRule: `zeebe:calledDecision` (decisionId/resultVariable/bindingType/versionTag) | `:2013-2017` | DMN-вызов | `BusinessRuleTaskIntegrationTests` | **авто** |
| boundary: attachedToRef / cancelActivity (interrupting, default true) | `:602-604` | `EventTrigger.fireBoundary` | все boundary-IT | **авто** |
| `<process>`: versionTag / historyTimeToLive / jobPriorityDefinition / userTaskForm list | `:119-136` | артефакты деплоя | `Camunda8ParityCharacterizationTests` | н/д |
| `<message>`/`<error>`/`<signal>`/`<escalation>` definitions | `BpmnDefinitionsModel.java:19-30` + резолв `:94-113` | реестры событий | `ErrorBoundaryIntegrationTests`, `SignalIntegrationTests`, `EscalationIntegrationTests` | н/д |
| `<association>` — связь compensation-границы с её handler'ом | `BpmnProcessDefinitionModel.java:86-87`; резолв `:412-422`, `:1705-1710` (вложенный скоуп), `:2414-2424` | `CompensationThrowHandler` | `CompensationIntegrationTests`, `CompensationScopeIntegrationTests` | **авто** |

---

## 4. Парсится/присутствует, но НЕ исполняется / тихо игнорируется / однобоко

| # | Конструкция | Факт (file:line) | Что реально происходит | Тест-страж |
|---|---|---|---|---|
| 1 | `boundaryEvent` без исполняемого event-definition (none/link/прочее) | `BpmnParseServiceImpl.java:394-396` — `continue` | **тихий дроп**: граница не попадает в модель, деплой не валится | **не найден** (стража отсутствия нет) |
| 2 | `<implicitThrowEvent>` | не в JAXB-модели (`BpmnProcessDefinitionModel.java:15-88`); в сканере — только в `FLOW_NODE_NAMES` (`:122`) для dangling-проверки | **тихо игнорируется** | `UnsupportedConstructDeployIntegrationTests.criterion6_refsToNodesTheParserDoesNotModel_areNotReportedAsDangling` (`:155`) — покрыт только как «не dangling», не как «не исполняется» |
| 3 | Choreography (`choreographyTask`/`subChoreography`/`callChoreography`) | не в JAXB; только `FLOW_NODE_NAMES:129-130` | **тихо игнорируется** | **не найдено** |
| 4 | Camunda-экспорт теги `<messageStartEvent>`/`<timerStartEvent>`/`<signalStartEvent>`/`<terminateEndEvent>` | JAXB биндит только `<startEvent>`/`<endEvent>` (`BpmnProcessDefinitionModel.java:37,:39`); сканер знает эти теги (`FLOW_NODE_NAMES:132`) | **тихо игнорируются** (модель их не видит); при этом поток из них НЕ считается dangling | `UnsupportedConstructDeployIntegrationTests.criterion6_refsToNodesTheParserDoesNotModel_areNotReportedAsDangling` (`:155`) |
| 5 | `collaboration` / `participant` / `laneSet` / `lane` (в BPMN 2.0 элемента `<pool>` нет — это `participant`) | не в `BpmnDefinitionsModel` (`:16-30`) | **тихо игнорируются** | `Camunda8ParityCharacterizationTests.poolsAndLanes_doNotAffectExecution` (`test-c8-pools.bpmn`) |
| 6 | `dataObject`/`dataObjectReference`/`dataStoreReference`/`itemDefinition`/`dataInputAssociation`/`dataOutputAssociation`/`ioSpecification`/`property` | не в JAXB | **тихо игнорируются** | `Camunda8ParityCharacterizationTests` (`test-c8-data-objects.bpmn`; в фикстуре — `<bpmn:dataInputAssociation>`) |
| 7 | `endEvent` + `compensate` | def прикрепляется (`BpmnParseServiceImpl.java:575-581`), но **тип элемента остаётся `END_EVENT`** (`:2247-2248`), а `EndEventHandler` не читает COMPENSATE-def (в файле нет ни одного упоминания `COMPENSATE`/`Compensat`) | конец ветки = обычное завершение; **компенсация на конце не исполняется** | `Camunda8ParityCharacterizationTests` — характеризация, а не исполнение |
| 8 | `BpmnElementType.EVENT` | `BpmnElementType.java:4` — в main-коде **0 ссылок** (единственное упоминание — в тесте `ActivityServiceImplTests:1187`) | мёртвое значение enum'а | — |
| 9 | Элемент без handler'а в реестре | `ActivityServiceImpl.java:148-157` | **инцидент** «Unsupported BPMN element type» при достижении (fail-closed на runtime, не на деплое) | `ActivityServiceImplTests` |
| 10 | executionListeners на **boundary**-элементах | `attachElementStartListeners` (`:1866-1894`) вызывается для exclusiveGateway (`:2300`), parallelGateway (`:2312`), eventBasedGateway (`:285`, вложенный `:1632`), inclusiveGateway (`:304`, вложенный `:1617`), end (`:2254`), start (`:2281`), catch (`:2366`), throw (`:2408`); у service/user-task — свои пути (`:1798`, `:2153-2196`) — **boundary в списке нет** | **не парсятся** — слушатели на границе молча теряются | **не найдено** |
| 11 | taskListeners вне `userTask` | парсятся только для `userTask` (`:2153-2196`) | **не парсятся** | **не найдено** |
| 12 | executionListeners на `sendTask` | `toElementModel(BpmnSendTaskModel):2028-2050` — вызываются только `attachTaskHeaders` (`:2043`), `attachElementStartListeners` (`:1866-1894`) для sendTask **не вызывается** (сайты — §4-10) | **не парсятся** — слушатели на send-task молча теряются | **не найдено** |
| 13 | ioMapping на `adHocSubProcess` | `attachIoMapping` вызывается для subProcess `:752`, serviceTask `:1804`, userTask `:2199`, callActivity `:2447` — **для adHoc нет** | **не парсится** — молча теряется | **не найдено** |
| 14 | MI на `transaction`/`adHocSubProcess`/`callActivity` | `BpmnSupportScanner.java:61-62` | **отказ на деплое** (не «молча один раз») | §1.1 #4 |
| 15 | MI на `task`/`scriptTask`/`manualTask`/`businessRuleTask`/`sendTask`/`receiveTask` | `:74-75` | **отказ на деплое** | §1.1 #5 |
| 16 | Обращения `<messageStartEvent>`/`<terminateEndEvent>` как «исполняемые» | `:132` — только для dangling-проверки | §4-4 | §4-4 |
| 17 | `<documentation>` (process-level) | биндится (`BpmnProcessDefinitionModel.java:30-32`), но **в модель не переносится** и нигде не читается | **тихо игнорируется** | **не найдено** |
| 18 | Прочие BPMN-элементы вне `@XmlElement`-биндинга (`textAnnotation`, `group`, `import`, `artifact`, `resourceRole`, `performer`, `correlationProperty`, `interface`, `itemDefinition`) | `BpmnProcessDefinitionModel.java:15-88` / `BpmnDefinitionsModel.java:16-30` — полный перечень биндинга | **тихо игнорируются** (для них нет ни отказа, ни парсинга) | **не найдено** |

---

## 5. FEEL-валидация при деплое (WO-C8-56) — что спрашивается у FEEL-парсера

Валидатор `FeelSyntaxValidator` (`zorrobpm-engine/.../bpmn/xml/FeelSyntaxValidator.java`) идёт по
**распарсенной модели** (не по сырому XML) и использует **тот же** `FeelEngineApi`, что и рантайм.
Разбор грамматики по полю — `FeelExpressionKind` (`:97-111`): `FLOW_CONDITION` (безусловный `substring(1)`,
unary tests), `UNARY_CONDITION` (снять один ведущий `=`, unary tests), `FEEL_EXPRESSION` (снять `=`,
expression), `FEEL_EXPRESSION_PLAIN` (как есть, без снятия).

| Поле | Политика | file:line | Покрыто |
|---|---|---|---|
| `sequenceFlow.condition` | всегда, если непусто | `:160-171` | `FeelDeployValidationIntegrationTests.invalidFlowCondition_singleQuotedString_rejectedWith400InvalidFeelExpression` (`:54`) |
| `userTask.assignee` / `candidateUsers` / `candidateGroups` / `dueDate` / `followUpDate` / `priorityDefinition` | FEEL только при ведущем `=` | `:320-333` | `…invalidUserTaskAssignee_rejectedNamingTheElementAndTheField` (`:83`) |
| `serviceTask.jobPriorityDefinition`, `process.defaultJobPriority` | FEEL только при ведущем `=` | `:150-157`, `:335-342` | `Camunda8ParityCharacterizationTests` |
| ioMapping **input** source | FEEL только при ведущем `=`; без `=` — статическое значение (допускается) | `:220-232` | `IoMappingStaticSourceIntegrationTests` |
| ioMapping **output** source | **обязан** начинаться с `=`; статическое значение → отказ | `:240-260` | `IoMappingStaticSourceIntegrationTests` |
| MI `inputCollection` / `outputElement` | **обязаны** начинаться с `=` (флаг исходного написания — `BpmnParseServiceImpl.java:2223,:2227`); без `=` → отказ | `:262-293` | `MiExpressionFieldDeployValidationIntegrationTests` (`:78,:107,:143`) |
| MI `inputElement` / `outputCollection` | имена, не выражения — **не валидируются** | `:278-280` | `…inputElementAndOutputCollection_areNamesAndAreNotRefused` (`:211`) |
| MI `loopCardinality` | FEEL только пока `inputCollection` пуст | `:281-289` | `MultiInstanceCardinalityValidationIntegrationTests` |
| MI `completionCondition` | снять ведущий `=` | `:290-292` | `MiTaskCompletionConditionSiblingsIntegrationTests` |
| adHoc `activeElementsCollection` / `outputElement` / `completionCondition` | raw (без снятия `=`) | `:295-307` | `AdHocSubProcessIntegrationTests` |
| script `zeebe:script` / inline `<script>` | raw | `:309-318` | `ScriptTaskIntegrationTests` |
| message `correlationKey` | снять ведущий `=` | `:348-352` | `MessageCorrelationKeyIntegrationTests` |
| timer (start/catch/boundary) / event-subprocess `triggerTimer` | FEEL только при ведущем `=` (ISO-duration/date/cron — не FEEL) | `:355-366` | `FeelTimerPgIT` |
| businessRule `expression` | только если `decisionId` пуст (иначе — мёртвый текст) | `:368-380` | `BusinessRuleTaskIntegrationTests` |
| businessRule `decisionId` | FEEL только при ведущем `=` | `:381-383` | `Camunda8ParityCharacterizationTests` |
| callActivity `processId` | FEEL только при ведущем `=` | `:386-393` | `Camunda8ParityCharacterizationTests` |
| conditional event `condition` | снять ведущий `=`, unary tests | `:395-408` | `ConditionalEventIntegrationTests` |
| `messageName` | **не FEEL** (литеральное имя подписки/throw'а) — не валидируется | `:350` | — |

---

## 6. Фикстуры и покрытие

- **384 файла** BPMN в `zorrobpm-engine/src/test/files/` (382 в корне + 2 в `files/integration/`; имена
  `process1.bpmn`/`process2.bpmn` встречаются в обоих каталогах, содержимое различается — мапа строилась
  по **полному относительному пути**, чтобы не схлопывать пару) **+ 23 файла** в
  `zorrobpm-rest/src/test/files/`. Мапа «фикстура → тест-классы» построена grep'ом имени файла по
  `zorrobpm-engine/src/test/java` + `zorrobpm-rest/src/test/java`.
- **383 из 384** engine-фикстур упомянуты хотя бы одним тест-классом; **единственная без ссылки** —
  `process0.bpmn` (тест на неё в дереве не найден).
- Формы написания, встречающиеся **только внутри тестов**, а не фикстурами:
  `<loopCharacteristics xsi:type=…>` (табличный тест ENG-34, inline-XML),
  `standardLoopCharacteristics` на всех видах хоста (табличный тест, inline-XML).

Распределение engine-фикстур по видам элемента (grep по тегу в XML):

| Элемент | Фикстур | Элемент | Фикстур |
|---|---|---|---|
| `serviceTask` | 89 | `exclusiveGateway` | 35 |
| `userTask` | 178 | `inclusiveGateway` | 35 |
| `subProcess` | 87 | `parallelGateway` | 68 |
| `boundaryEvent` | 85 | `eventBasedGateway` | 4 |
| `multiInstanceLoopCharacteristics` | 73 | `complexGateway` | 2 (1 — характеристика «игнорируется», 1 — отказ) |
| `scriptTask` | 33 | `standardLoopCharacteristics` | 1 |
| `intermediateCatchEvent` | 28 | `implicitThrowEvent` | 1 |
| `intermediateThrowEvent` | 25 | `task` (без типа) | 2 |
| `callActivity` | 24 | `messageStartEvent`/`timerStartEvent` | 2 / 2 (в одном файле) |
| `adHocSubProcess` | 20 | `terminateEndEvent` | 1 |
| `businessRuleTask` | 10 | `transaction` | 8 |
| `sendTask` / `receiveTask` | 4 / 4 | `manualTask` | 4 |
| `startEvent` / `endEvent` | 382 / 382 | `choreography*` | 0 |

---

## 7. Инвариант утечек G-P: механизм (WO-OPS-26) и его границы

### 7.1 Механизм

- **Реализация:** `zorrobpm-engine/src/test/java/com/zorrodev/bpm/engine/leak/LeakInvariantChecker.java`
  (проверка) + `LeakInvariantExtension.java` (JUnit-расширение + Spring-listener).
- **Автоподключение (правок pom/`ci/` нет):**
  `zorrobpm-engine/src/test/resources/junit-platform.properties` → `junit.platform.extensions.autodetection.enabled=true`,
  `zorrobpm-engine/src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension` →
  `com.zorrodev.bpm.engine.leak.LeakInvariantExtension`;
  `zorrobpm-engine/src/test/resources/META-INF/spring.factories` → `LeakInvariantExtension$LeakInvariantTestListener` (order 3000).
- **Область:** ТОЛЬКО модуль `zorrobpm-engine`. Тесты `zorrobpm-rest` (159 классов с `@SpringBootTest`)
  расширение **не получают** — в `zorrobpm-rest/src/test/resources` нет ни `junit-platform.properties`,
  ни `META-INF/services` (проверено: там только `application.properties`/`application-prod.yml`).
- **Что проверяется** (`LeakInvariantChecker.check:55-216`) для инстансов, завершённых (`completed_at != null`)
  после baseline: (а) нет activity в `CREATED`/`IN_PROGRESS` (`:71-78`); (б) нет висящих `user_tasks`/
  `service_tasks` с живой activity-строкой (`:91-110`); (в) нет несработавших `timer_jobs` на **живой** activity
  (`:147-156`); (г) нет непотреблённых `message_subscriptions`/`signal_subscriptions` инстанса (`:160-175`);
  (д) нет остаточных arrival-строк `parallel_gateways` (`:181-189`); (е) нет scope-переменных в **отменённом**
  инстансе, кроме `_mi_batch_*` (`:197-213`).
- **Порядок:** проверка выполняется в фазе `afterTestExecution` (order 3000) — ДО отката
  `@Transactional` (order 4000). Проверено тестом `LeakInvariantExtensionTests.orderProbe_*`.

### 7.2 Чего инвариант НЕ проверяет (важно для ФАЗ 1–5)

| # | Пробел | Почему | file:line |
|---|---|---|---|
| L-1 | **Токены с `pending_branches > 0`** | блок проверки **выключен** (`if (false)`): сигнал не подтверждён ни одним живым примером | `LeakInvariantChecker.java:127-137` |
| L-2 | **Инстанс, который НЕ завершился** (завис) | инвариант смотрит только `completed_at IS NOT NULL` | `LeakInvariantChecker.java:56-60` |
| L-3 | **Scope-переменные нормально завершённых** инстансов | сознательно не считаются мусором (история `loopCounter`/`item`) | `LeakInvariantChecker.java:191-196` |
| L-4 | **Живые activity с другими статусами** (`ERROR`, `CANCELLED`) | `ERROR` исключён сознательно (история, не мусор) | `LeakInvariantChecker.java:69-70` |
| L-5 | **Открытые инциденты** | инциденты — не часть инварианта; «нет инцидентов» проверяется отдельными ассертами в тестах (колонка «инцидент» в §2/§3) | — |
| L-6 | **Дефектные тесты модуля `zorrobpm-rest`** | расширение живёт только в engine | §7.1 |
| L-7 | **Задания/джобы в очереди** (`service_tasks` без activity-строки) | не проверяется | — |
| L-8 | **MI-маркеры `_mi_batch_*`** | сознательно исключены из проверки scope-переменных | `LeakInvariantChecker.java:196,:208` |

### 7.3 Режим: `report` по умолчанию, `enforce` нигде не включён

- `LeakInvariantExtension.mode()` (`:204-206`): по умолчанию **`report`** — нарушения пишутся в
  `<user.dir>/target/leak-invariant-report.txt` (`:407-419`), **тест не падает**.
- `enforce` (`-Dzbpm.leakInvariant=enforce`, `:194-201`) **не включён ни в одном pom.xml, ни в `ci/`, ни в
  `.gitlab-ci.yml`** — проверено grep'ом по всему репозиторию (0 совпадений вне самого расширения).
- Allowlist (`leak-invariant-allowlist.txt`) содержит **3 строки, и все три — комментарии**, т.е.
  известных-принятых нарушений нет.

**Следствие для кампании:** «G-P: авто» означает «проверка выполнена и её результат записан в отчёт», а не
«тест упал бы при утечке». Единственный вид доказательства, который краснеет, — **собственный** ассерт
теста на отсутствие остатков. Поэтому в §2/§3 колонка «свой» — это то, что реально защищает от регрессии;
«авто» — детектор, результат которого надо уметь читать (`target/leak-invariant-report.txt`).

### 7.4 Что детектор реально нашёл (замер полного прогона движка)

Полный `mvn -B -ntp clean verify` на дереве этой ветки (код = master `87bdda7e4`) отработал
`BUILD SUCCESS` за 9 мин 37 с; попутно детектор инварианта (режим `report`) записал
`zorrobpm-engine/target/leak-invariant-report.txt`:

| Величина | Значение |
|---|---|
| строк-нарушений всего | **1077** |
| тест-классов, в которых они найдены | **45** |
| строк в allowlist (`allowed \| …`) | **0** (allowlist пуст) |
| по типам нарушений | `task` — 701, `activity` — 345, `subscription` — 18, `arrival` — 11, `timer` — 1, `scope-var` — 1 |
| топ-классов | `FeelIoMappingConformanceIntegrationTests` — 282, `InclusiveJoinReadinessMatrixIntegrationTests` — 218, `DmnEvaluationIntegrationTests` — 51, `UserTaskCandidateQueryIntegrationTests` — 39, `InclusiveJoinReadinessIntegrationTests` — 36, `FinishedInstanceArrivalCleanupIntegrationTests` — 36, `MiTaskCompletionConditionSiblingsIntegrationTests` — 33, `IoMappingStaticSourceIntegrationTests` — 33 |

**Что из этого следует (осторожно — это замер, а не диагноз).** Отчёт не различает два разных случая:
(а) реальная утечка в сценарии, (б) артефакт общей H2-базы — «чужая» завершённая строка, попавшая в
baseline-дельту (этот класс артефактов прямо описан в javadoc самого `LeakInvariantChecker`: диагностика
H2d-прогона дала 1107 token-нарушений, из них 664 в `Camunda8ParityCharacterizationTests`, и авторы
приняли решение **не** считать `pending_branches > 0` детектором живых утечек). Разделить (а) и (б)
по этому файлу нельзя — нужен прогон на PostgreSQL с изоляцией (`ci/run-pg-tests.sh`) и/или поштучный
разбор.

**Практический вывод для ФАЗ 5 и для `enforce`:** включить `enforce` («-Dzbpm.leakInvariant=enforce») на
текущем дереве нельзя — падать будут 45 классов, и причины надо сначала разобрать (часть — артефакты
общей БД). Это ровно та работа, что заведена отдельной задачей (ветка `fix/ops-32-leak-enforce`).

---

## 8. Пустые/односторонние места — сводка (кандидаты ФАЗ 1–5)

**Как читать этот раздел.** Полный и единственный статус по каждому элементу/свойству — колонка G-P в
§2/§3. Здесь — **сводка кандидатов**: P-1…P-7 — конструкции, которые движок НЕ исполняет или НЕ парсит
(тут список полный); P-8…P-18 — элементы/свойства, исполняемые, но без собственного ассерта на остаток
(список полный, P-9); P-11…P-16 — пробелы самого инварианта и прочие находки.

| # | Место | Что пусто | Кандидат |
|---|---|---|---|
| P-1 | §4-1 | `boundaryEvent` без event-definition — **тихий дроп, стража нет** | ФАЗА 4 (события) |
| P-2 | §4-3, §4-18 | choreography / textAnnotation / group / import и прочие небиндящиеся элементы — тихо игнорируются, стража нет (для dataObjects — есть) | ФАЗА 3/5 |
| P-3 | §4-7 | `endEvent` + `compensate` — **компенсация не исполняется** | ФАЗА 4 |
| P-4 | §4-10 | executionListeners на boundary — **не парсятся** | ФАЗА 4 |
| P-5 | §4-11, §4-12 | taskListeners вне userTask и executionListeners на sendTask — **не парсятся** | ФАЗА 1 |
| P-6 | §4-13 | ioMapping на adHoc — **не парсится** | ФАЗА 3 |
| P-7 | §1.1 #9 vs §4-4 | Camunda-экспорт теги (`<messageStartEvent>` и т.п.) **молча выпадают** из модели | ФАЗА 4 |
| P-8 | §2 | `exclusiveGateway` — своего ассерта на остаток нет | ФАЗА 2 |
| P-9 | §2/§3 | **Полный** список строк без своего ассерта на остаток — **71 из 91** (в §2 — 41 из 54, в §3 — 30 из 37). Это ровно те строки §2/§3, где в колонке G-P нет метки «свой»; перечислять их здесь повторно не нужно — полный и единственный статус по каждому элементу/свойству даёт колонка G-P. Кандидаты на ФАЗЫ 1–5 из этого множества — по приоритету: задачи (service/user/script/businessRule/send/receive) и их свойства, exclusiveGateway, eventBasedGateway, callActivity, все варианты intermediateCatch/Throw, adHoc, eventSubProcess, boundaryEvent (error/signal/escalation/conditional/compensate/cancel), transaction и subProcess без своего ассерта, ioMapping/FEEL-свойства (condition, defaultFlow, correlationKey, calledDecision, script, waitForCompletion, conditionalFilter, job-based end/throw, `<process>`-уровневые свойства, definitions). |
| P-10 | §2 | `boundaryEvent` + error / signal / escalation / conditional — своих ассертов нет | ФАЗА 4 |
| P-11 | §7.2 L-1 | блок «токены с `pending_branches > 0`» **выключен** | ФАЗА 5 (сквозные) |
| P-12 | §7.2 L-2 | зависший (не завершённый) инстанс **не виден** инварианту | ФАЗА 5 |
| P-13 | §7.1 | тесты `zorrobpm-rest` (159 Spring-классов) **не покрыты** инвариантом | ФАЗА 5 |
| P-14 | §7.3 | `enforce` **не включён** нигде — инвариант не роняет тесты | ФАЗА 5 (или отдельный WO) |
| P-15 | §6 | `process0.bpmn` — фикстура без ссылки ни на один тест | ФАЗА 1 |
| P-16 | §4-8 | `BpmnElementType.EVENT` — мёртвое значение enum'а | мелочь (V7) |
| P-17 | §2, последняя строка | `boundaryEvent` на MI-контейнере: семантика «на теле MI vs per-instance» (расхождение ENG-3) **не разделена**, свой ассерт на остаток в `MiBoundaryTimerPgIT` отсутствует | ФАЗА 4 |
| P-18 | §2, `intermediateThrowEvent`+`compensate` / `boundaryEvent`+`compensate` | в `CompensationWaitIntegrationTests` / `CompensationScopeIntegrationTests` / `CompensationIntegrationTests` absence-ассертов нет — компенсация проверяется по статусам, а не по остаткам | ФАЗА 4 |

---

## 9. Методология и оговорки

1. Каждая строка §1–§5 — это чтение файла на ветке `fix/ops-28-phase0` (master `87bdda7e4`) плюс grep
   по дереву. Строки, которые не удалось подтвердить по диску, помечены «не найдено» / «не знаю» —
   **домысливать нельзя** (P-1).
2. **«G-P: свой» = в классе есть ассерт на ОТСУТСТВИЕ остатка по списку G-P** (`isEmpty()`/`isZero()`/
   `isEqualTo(0)`/`hasSize(0)`/`doesNotContain` по живым activity, task-строкам, таймерам, подпискам,
   arrival-строкам, scope-переменным, `_mi_batch_*`, pending-listener). Проверка «активность ЕСТЬ
   `CREATED`» (`isEqualTo(CREATED)`) — это **положительный** ассерт, остатком он не является: так
   устроены `ConditionalEventIntegrationTests` (`:113` и далее), `NoneEndEventPgIT` (`:155`),
   `TerminateScopeIntegrationTests` (`:89`), `ReceiveTaskBoundaryIntegrationTests` (`:141`),
   `ContainerSubscriptionCloseIntegrationTests` (`:84`) — в «свой» они не попадают.
   **«G-P: инцидент» = ассерт «нет открытых инцидентов»** — родственный маркер, но чекер инциденты не
   проверяет (§7.2 L-5).
3. Карта «фикстура → тест» построена grep'ом имени файла по `src/test/java` (engine + rest); один файл может
   покрываться несколькими классами — в таблицах названы главные, не все.
4. «авто» в колонке G-P — НЕ «тест упал бы при утечке»: см. §7.3 (режим `report`, `enforce` не включён).
5. Границы фазы 0: не проверялась **семантика** FEEL-выражений (только факт парсинга и факт деплой-валидации),
   не проверялся runtime-путь AMQP-воркеров (только факт enqueue/handler'а), не проверялись REST-эндпоинты
   `ProcessInstance*Resource` (вне BPMN-деплоя), не снят прогон с Oracle Zeebe 8.5.0 — это ФАЗЫ 1–5
   (по WO-OPS-27).
6. **Код не менялся.** Изменены только `docs/analysis/bpmn-conformance-inventory.md`,
   `docs/internal` и `docs/internal` (канал).
