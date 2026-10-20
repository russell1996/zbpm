# BPMN Conformance Inventory — WO-OPS-28, ФАЗА 0 (read-only)

- **Дата:** 2026-10-21
- **Ветка:** `fix/ops-28-phase0` от локального master `87bdda7e4` (Merge WO-AUDIT-17)
- **Режим:** read-only анализ кода. Правки в `src/main`, `src/test`, фикстуры и `ci/` **НЕ вносились**;
  изменён только этот файл и отчёт `docs/internal`.
- **Метод:** факт с диска (V1). Источники: `BpmnSupportScanner.java`, `BpmnParseServiceImpl.java`,
  `BpmnProcessDefinitionModel.java`/`BpmnDefinitionsModel.java` (JAXB-биндинг),
  `FeelSyntaxValidator.java`, `ProcessDefinitionServiceImpl.java` (деплой-гейт), `HandlerRegistry.java`,
  `ActivityServiceImpl.java`, `handler/*Handler.java`, `BoundaryScheduler.java`, `EventTrigger.java`,
  `ErrorEscalationThrower.java`, `MultiInstanceExecutor.java`, фикстуры
  `zorrobpm-engine/src/test/files/**/*.bpmn` (384 файла) и 711 тест-классов engine+rest
  (211 engine + 159 rest с `@SpringBootTest`).
- **Предыдущая редакция:** `44421ad79` (2026-10-14, «Qwen», спот-чек maintainer `cbbda4ac2`) — этот документ
  ПЕРЕПИСАН на текущий master; дельта вынесена в §0, ничего из старой редакции не потеряно молча.

**Как читать колонку «G-P» (инвариант утечек, правило G-P в `CONTRIBUTING.md`).**

| Значение | Что означает |
|---|---|
| **авто** | элемент покрыт глобальной автопроверкой инварианта утечек (WO-OPS-26, §7): для инстанта, завершённого после baseline, нет activity в `CREATED`/`IN_PROGRESS`, нет висящих `user_tasks`/`service_tasks`, нет несработавших `timer_jobs` на живой activity, нет непотреблённых `message`/`signal`-подписок, нет остаточных arrival-строк `parallel_gateways`, нет scope-переменных в отменённом инстансе. Режим по умолчанию — `report` (§7.3), т.е. проверка ВЫПОЛНЯЕТСЯ, но тест не падает. |
| **свой** | кроме «авто» у теста есть СВОЙ ассерт на отсутствие остатков (приведён `file:line`) — это единственный вид проверки, который даёт «зелёный/красный» в отчёте. |
| **нет** | ни своей проверки остатков, ни покрытия автопроверкой (тест без Spring-контекста или элемент вообще не проверен). |
| **н/д** | элемент не исполняется (деплой-отказ) — инвариант неприменим. |

---

## 0. Дельта против редакции 2026-10-14 (`44421ad79`)

За неделю master ушёл на 410 коммитов (55 — engine `src/main`, 85 — engine `src/test`). Что изменилось
**по существу для инвентаря**:

| # | Изменение | Где | Следствие для кампании |
|---|---|---|---|
| D-1 | **Новый код деплой-отказа** `UNSUPPORTED_MULTI_INSTANCE_SHAPE`: MI-маркер внутри `<bpmn:extensionElements>` хоста, который парсер не биндит (всё, кроме `serviceTask`/`userTask`/`subProcess`) | `BpmnSupportScanner.java:244-254` (список хостов `:85-86`), код `:743` | §1: +1 отказ. Покрыт `MiNestedCompletionConditionIntegrationTests.nestedMiOnTransaction_deployRefusedWithShapeCode` (`:144`) |
| D-2 | **Новый деплой-гейт FEEL**: `INVALID_FEEL_EXPRESSION` (400) — тот же FEEL-парсер, что и в рантайме, спрашивает «принимает ли он это выражение?» на распарсенной модели | `FeelSyntaxValidator.java:147-191` (обход полей), гейт `ProcessDefinitionServiceImpl.java:173-194` | §5 — новая секция: перечень полей, которые валидируются/не валидируются на деплое. Покрыт `FeelDeployValidationIntegrationTests` (5 тестов) |
| D-3 | **ioMapping output source без «=»** — отказ на деплое (статическое значение там не выражение, live 8.5) | `FeelSyntaxValidator.java:240-260` | §3 (ioMapping) |
| D-4 | **MI `inputCollection`/`outputElement` без «=»** — отказ на деплое; `inputElement`/`outputCollection` — имена, не выражения, не валидируются | `FeelSyntaxValidator.java:262-293`, флаг исходного написания `BpmnParseServiceImpl.java:2208-2234` | §3 (MI). Покрыт `MiExpressionFieldDeployValidationIntegrationTests` (5 тестов) |
| D-5 | **MI на embedded `subProcess`** — поддерживается (WO-C8-40): MI-вход, агрегация, join в `MultiInstanceExecutor` | `BpmnSupportScanner.java:61-62` (список НЕ-поддерживаемых контейнеров — `transaction`/`adHocSubProcess`/`callActivity`), `BpmnParseServiceImpl.java:355` | §2 (subProcess + MI) — 4 фикстуры `test-c840-*.bpmn`, 4 теста |
| D-6 | **Глобальный инвариант утечек (G-P)** — авто-проверка для КАЖДОГО engine-теста с Spring-контекстом (JUnit-autodetection + `spring.factories`) | `zorrobpm-engine/src/test/java/.../leak/LeakInvariantChecker.java`, `LeakInvariantExtension.java` | §7 — новая секция; меняет смысл колонки «G-P» с «свой ассерт» на «авто + свой» |
| D-7 | **Нормализация `incoming`/`outgoing` из графа `sequenceFlow`** — авто-модель больше не зависит от того, объявил ли автор `<bpmn:incoming>/<bpmn:outgoing>` | `BpmnParseServiceImpl.java:424-428`, `:478-538` | §2 (потоки) — новое поведение, ранее не инвентаризировалось |
| D-8 | **MI `outputElement` — instance-local**, ordered output mappings; ioMapping null-семантика как в Zeebe 8.5 | `MultiInstanceExecutor`, `ElementSupport` | §3 |
| D-9 | **`completionCondition` MI-задачи терминирует сиблингов** | `MiTaskCompletionConditionSiblingsIntegrationTests` | §2/§3 (MI) |
| D-10 | **error-end паркуется в `ERROR`** (не завершает ветку молча), каскад отмены дочерних call-activity | `ErrorEscalationThrower`, `Audit12CancelCascadePgIT` | §2 (endEvent + error) |
| D-11 | Старый документ не содержал `INVALID_HISTORY_TTL` (WO-ENG-17) — деплой-отказ на мусорный `camunda:historyTimeToLive` | `ProcessDefinitionServiceImpl.java:282-304` | §1: +1 отказ |
| D-12 | Старый документ не содержал §5 (FEEL на деплое) и не различал «свой ассерт» и «авто-проверка» | — | §5, §7 |

**Что НЕ изменилось и подтверждено заново на текущем master:** состав поддерживаемых элементов (§2),
свойства (§3), список «тихо игнорируемых» конструкций (§4), карта фикстур (§6).

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
| 3 | `standardLoopCharacteristics` (прямое написание) | `:174-182` | `UNSUPPORTED_STANDARD_LOOP` (`:720`) | `…criterion1_standardLoopCharacteristics_rejectedAndNothingPersisted` (`:67`); `LoopCharacteristicsSpellingDeployTests.criterion1_standardLoopWrittenDirectly_rejectedOnEveryHostKind` (`:133`) |
| 4 | MI на `transaction` / `adHocSubProcess` / `callActivity` | `:188-198` (список `:61-62`) | `UNSUPPORTED_MULTI_INSTANCE_CONTAINER` (`:721`) | `…criterion5_multiInstanceOnContainer_rejected` (`:225`); `MultiInstanceOnContainersParseTest.multiInstanceOnCallActivity_isRefusedAtDeployNamingIt` (`:58`) |
| 5 | MI на `task`/`scriptTask`/`manualTask`/`businessRuleTask`/`sendTask`/`receiveTask` | `:203-213` (список `:74-75`) | `UNSUPPORTED_MULTI_INSTANCE_TASK` (`:728`) | `LoopCharacteristicsSpellingDeployTests.multiInstanceWrittenDirectly_refusedOnContainersAndTasks_acceptedOnSupportedKinds` (`:153`) |
| 6 | MI, записанное как `<loopCharacteristics xsi:type="…">` (заголовок схемы) | `:220-231` (тип резолвится **через namespace**, не по тексту префикса — `:441-463`) | `UNSUPPORTED_MULTI_INSTANCE_XSI_TYPE` (`:734`) | `LoopCharacteristicsSpellingDeployTests.multiInstanceWrittenAsAbstractHeadElement_rejectedOnEveryHostKind` (`:113`), `criterion1_standardLoopWrittenAsAbstractHeadElement_rejectedOnEveryHostKind` (`:85`), `xsiTypeIsResolvedThroughTheNamespace_notThePrefixText` (`:202`) |
| 7 | MI внутри `<bpmn:extensionElements>` хоста, который парсер не биндит | `:244-254` (список хостов `:85-86`) | `UNSUPPORTED_MULTI_INSTANCE_SHAPE` (`:743`) | `MiNestedCompletionConditionIntegrationTests.nestedMiOnTransaction_deployRefusedWithShapeCode` (`:144`) |
| 8 | Conditional start event | `:257-265` | `UNSUPPORTED_CONDITIONAL_START_EVENT` (`:744`) | `…criterion5_conditionalStartEvent_rejectedNotSilentlyMadeAPlainStart` (`:207`) |
| 9 | `sequenceFlow` с `sourceRef`/`targetRef` на несуществующий flow node | `:269-276` (резолв — по **документу**, не по модели парсера: `:564-593`) | `UNRESOLVED_SEQUENCE_FLOW_REF` (`:745`) | `…criterion4_unresolvableFlowRef_rejectedNamingTheFlow` (`:138`), `criterion4_danglingRefInsideASubProcess_isFoundToo` (`:178`), `criterion4_refToANodeOfTheParentScopeInsideASubProcess_isNotDangling` (`:192`), `criterion6_refsToNodesTheParserDoesNotModel_areNotReportedAsDangling` (`:154`) |

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
| 14 | Нет ни одного start event | `BpmnParseServiceImpl.checkBpmn:1738-1740` | `BpmnParseException` → HTTP 400 `PARSE_ERROR` (`GlobalExceptionHandler.java:151-161`; текст — только в лог) | `BpmnParseServiceImplTest` |
| 15 | Больше одного «простого» (none) start event | `:1741-1755` | `BpmnParseException` → 400 `PARSE_ERROR` | `BpmnParseServiceImplTest` |
| 16 | Нет ни одного end event | `:1756-1758` | `BpmnParseException` → 400 `PARSE_ERROR` | `BpmnParseServiceImplTest` |
| 17 | Ad-hoc subProcess со start event внутри | `BpmnParseServiceImpl.java:1374-1376` | `BpmnParseException` → 400 `PARSE_ERROR` | `BpmnParseServiceImplTest` |

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
| `startEvent` + `timer` (date/duration/cycle) | `:152-168`, тип `:2273-2274` | alias → `StartThrowEventHandler`; `registerTimerStartJobs` | `TimerStartIntegrationTests`, `TimerStartCycleIntegrationTests`, `RepeatingTimerSemanticsPgIT` | **авто** + **свой** (`RepeatingTimerSemanticsPgIT:147,:197` — «no 4th re-arm», `remaining_count == 0`) |
| `startEvent` + `signal` | тип `:2275-2276` | alias → `StartThrowEventHandler`; `registerSignalStartSubscriptions` | `SignalStartBoundaryIntegrationTests` | **авто** |
| `startEvent` + `conditional` | **отвергается** (§1.1, #8) | — | — | н/д |
| `endEvent` (none) | `:195-201`, тип `:2247-2248` | `EndEventHandler.EndEvent` (`:36`) | `NoneEndEventPgIT`, `Process1–8IntegrationTests` | **авто** + **свой** (`NoneEndEventPgIT:155` — живой счётчик `CREATED`) |
| `endEvent` + `terminate` | тип `:2239-2240` | `EndEventHandler.TerminateEndEvent` (`:87`) | `TerminateEndIntegrationTests`, `TerminateScopeIntegrationTests`, `TerminateSubscopeTeardownIntegrationTests` | **авто** + **свой** (`TerminateScopeIntegrationTests:89`) |
| `endEvent` + `error` | тип `:2241-2242`, def `:549-552` | `EndEventHandler.ErrorEndEvent` (`:220`) + `ErrorEscalationThrower.throwError` (`:80`) | `Camunda8ParityCharacterizationTests`, `ErrorEscalationThrowerTest`, `Audit11ErrorEndAndForkScopeIntegrationTests` | **авто** + **свой** (`Audit11ErrorEndAndForkScopeIntegrationTests:170`) |
| `endEvent` + `escalation` | тип `:2243-2244`, def `:558-560` | `EndEventHandler.EscalationEndEvent` (`:281`) + `ErrorEscalationThrower.throwEscalation` (`:394`) | `EscalationIntegrationTests` | **авто**, своего ассерта на остатки нет |
| `endEvent` + `cancel` | тип `:2245-2246` | `CancelEndHandler` (`:49`) | `ForkCancelInTransactionIntegrationTests`, `Audit12CancelCascadePgIT` | **авто** + **свой** (`ForkCancelInTransactionIntegrationTests:95`) |
| `endEvent` + `compensate` | def `:575-581`, **тип остаётся `END_EVENT`** (`:2247-2248`) | **компенсация не исполняется** — см. §4-7 | `Camunda8ParityCharacterizationTests` (характеризация) | **авто**; своего нет |
| `serviceTask` | `:203-209`, `:1761-1808` | `ServiceTaskHandler` (`:32`) → AMQP-воркер | `ServiceTaskFailureIntegrationTests`, `CompleteServiceTaskPhaseResumePgIT`, `ListenerDuplicateCompletionTests` | **авто** + **свой** (`ListenerDuplicateCompletionTests:107` — pending listener index = 0) |
| `sendTask` | `:210-216`, `:2028-2050` (job-форма `:2037-2043`, message-форма `:2044-2048`) | `SendTaskHandler` (`:29`) | `SendReceiveTaskIntegrationTests`, `Camunda8ParityCharacterizationTests` | **авто** |
| `receiveTask` | `:217-223`, `:2052-2067` | alias → `MessageCatchHandler` (`:34`) | `SendReceiveTaskIntegrationTests`, `ReceiveTaskBoundaryIntegrationTests` | **авто** + **свой** (`ReceiveTaskBoundaryIntegrationTests:141`) |
| `manualTask` | `:224-230`, `:2069-2077` | `SyncTaskHandler.ManualTask` (`:101`) | `Camunda8ParityCharacterizationTests`, `BpmnStructureCoverageTest` | **авто** |
| `task` (без типа) | `:231-238`, `:2082-2090` | `SyncTaskHandler.UndefinedTask` (`:138`) | `BpmnStructureCoverageTest` (`test-diff9-untyped-task.bpmn`) | **авто** |
| `scriptTask` | `:239-245`, `:1948-1987` | `SyncTaskHandler.ScriptTask` (`:35`) | `ScriptTaskIntegrationTests`, `Camunda8ParityCharacterizationTests` | **авто** |
| `businessRuleTask` | `:246-252`, `:2005-2026` (`calledDecision` `:2013-2017`, FEEL `:2018-2021`) | `SyncTaskHandler.BusinessRuleTask` (`:166`) | `BusinessRuleTaskIntegrationTests` | **авто** |
| `userTask` | `:253-259`, `:2092-2203` | `UserTaskHandler` (`:31`) | `UserTaskQueryIntegrationTests`, `CompleteUserTaskCharacterizationPgIT`, `Camunda8ParityCharacterizationTests` | **авто** |
| `exclusiveGateway` | `:260-267`, `:2287-2302` (defaultFlow `:2294-2298`) | `ExclusiveGatewayHandler` (`:30`) | `Diff2ExclusiveGatewayOrderTest`, `ExclusiveGatewayUnsupportedShapeIntegrationTests`, `GatewayNoDefaultIntegrationTests`, C8-35-семья | **авто**; собственного ассерта «после XOR ничего не висло» — нет |
| `parallelGateway` | `:268-275`, `:2304-2314` | `ParallelGatewayHandler` (`:35`) | `ImplicitForkPendingBranchesIntegrationTests`, `ParallelGatewayDegenerateIntegrationTests`, `NoneEndEventPgIT` | **авто** + **свой** (`ImplicitForkPendingBranchesIntegrationTests`) |
| `eventBasedGateway` | `:276-289` | `EventBasedGatewayHandler` (`:26`) | `EventBasedGatewayIntegrationTests`, `TimerMessageQueryIntegrationTests` | **авто** |
| `inclusiveGateway` | `:290-308` (defaultFlow `:298-302`) | `InclusiveGatewayHandler` (`:35`) | `InclusiveGatewayIntegrationTests`, `InclusiveJoinReadinessMatrixIntegrationTests` (матрица), `InclusiveJoinReadinessIntegrationTests`, `InclusiveJoinDeactivationWakeupIntegrationTests` | **авто** + **свой** (`InclusiveJoinReadinessIntegrationTests:143`, `InclusiveJoinDeactivationWakeupIntegrationTests:152`) |
| `intermediateCatchEvent` + `message` | `:315-323`, тип `:2323-2324` | `MessageCatchHandler` (`:34`) | `Camunda8ParityCharacterizationTests`, `MessageSubscriptionFanOutIntegrationTests`, `ContainerSubscriptionCloseIntegrationTests` | **авто** + **свой** (`ContainerSubscriptionCloseIntegrationTests:84`) |
| `intermediateCatchEvent` + `timer` | тип `:2325-2326`, таймер `:2337-2353` | `TimerCatchHandler` (`:35`) | `ActivityServiceImplTests`, `FeelTimerPgIT`, `BoundedTimerIntegrationTest` | **авто** |
| `intermediateCatchEvent` + `signal` | тип `:2327-2328` | `SignalCatchHandler` (`:30`) | `SignalIntegrationTests` | **авто** |
| `intermediateCatchEvent` + `link` | тип `:2329-2330` | alias → `StartThrowEventHandler` | `LinkEventIntegrationTests` | **авто** |
| `intermediateCatchEvent` + `conditional` | тип `:2331-2332`, def `:561-571` | `ConditionalCatchHandler` (`:36`) | `ConditionalEventIntegrationTests` | **авто**; ассерты в классе — **положительные** (`…:113` и далее — «activity IS `CREATED`»), отсутствия остатков нет |
| `intermediateCatchEvent` (без def) | `:315-323`, тип `:2333-2334` | `WaitStateHandler` (`:26`) | `ActivityServiceImplTests` | **авто** |
| `intermediateThrowEvent` + `message` | `:325-334`, тип `:2378-2379` | `MessageThrowHandler` (`:34`) | `ActivityServiceImplTests` | **авто** |
| `intermediateThrowEvent` + `signal` | тип `:2385-2386` | `SignalThrowHandler` (`:31`) | `SignalIntegrationTests` | **авто** |
| `intermediateThrowEvent` + `escalation` | тип `:2387-2388` | `StartThrowEventHandler.StartEscalationThrow` (`:51`) | `EscalationIntegrationTests` | **авто** |
| `intermediateThrowEvent` + `link` | тип `:2389-2390` | `LinkThrowHandler` (`:31`) | `LinkEventIntegrationTests` | **авто** |
| `intermediateThrowEvent` + `compensate` | тип `:2391-2401` (activityRef `:2394-2401`), `waitForCompletion` `:578-581` | `CompensationThrowHandler` (`:40`) | `CompensationIntegrationTests`, `CompensationWaitIntegrationTests`, `CompensationScopeIntegrationTests` | **авто** + **свой** (`CompensationWaitIntegrationTests:96`) |
| `intermediateThrowEvent` (без def) | тип `:2402-2403` | `IntermediateThrowEventHandler` (`:27`) | `ActivityServiceImplTests` | **авто** |
| `callActivity` | `:336-342`, `:2426-2450` (calledElement `:2436-2444`, ioMapping `:2447`) | `CallActivityHandler` (`:40`) | `CallActivityVariablePropagationIntegrationTests`, `PropagateChildVariablesIntegrationTests`, `CallActivityNullSafetyIntegrationTests`, `CallActivityRecursionIntegrationTests`, `Audit12CancelCascadePgIT` | **авто**; своего ассерта на остатки нет |
| `subProcess` (embedded) | `:344-359`, `toSubProcessElement:651-744` (плоскость `:668-744`, MI `:355`) | `SubProcessHandler` (`:35`) | `SubProcessIntegrationTests`, `NestedTokenPendingBranchesIntegrationTests`, `Diff1IoMappingScopeReproTest` | **авто** + **свой** (`NestedTokenPendingBranchesIntegrationTests`) |
| `subProcess` + MI | `:355` (`attachSubProcessMultiInstance`), `:793-861` | `MultiInstanceExecutor` | `MiSubProcessIntegrationTests`, `MiSubProcessMinimalPgIT`, `MiSubProcessRaceIntegrationTests`, `MiSubProcessApprovalRouteIntegrationTests`, `MiSubProcessOrphanContainerIntegrationTests` | **авто** + **свой** (`MiSubProcessIntegrationTests:202` — `activities(pi,"utProbe",CREATED)).isEmpty()`, `MiSubProcessOrphanContainerIntegrationTests:151` — `liveActivities(pi)).isZero()`) |
| `subProcess` + MI (Camunda8-parity) | фикстуры `test-c841-parity-*.bpmn` | `MultiInstanceExecutor` | `MiSubProcessCamunda8ParityIntegrationTests` | **авто** + **свой** (`:425`) |
| `subProcess` + MI (loop-var/reentry) | фикстуры `test-c842-*.bpmn` | `MultiInstanceExecutor` + scope-chain | `MiSubProcessLoopVarVisibilityIntegrationTests` | **авто** + **свой** (`:293`) |
| `transaction` | `:363-371` (парсится как `SUB_PROCESS`; cancel-семантика — через cancel-end/cancel-boundary) | `SubProcessHandler` + `CancelEndHandler` | `TransactionCancelIntegrationTests`, `ForkCancelInTransactionIntegrationTests` | **авто** + **свой** (`ForkCancelInTransactionIntegrationTests:95`) |
| `eventSubProcess` (interrupting) | `:655-656` (тип по `triggeredByEvent`), `:668-744` | **отдельного handler'а нет** — `EventTrigger.triggerEventSubprocess:418`; error-триггер `ErrorEscalationThrower:186` | `EventSubProcessIntegrationTests`, `NestedEventSubprocessCancelScopeIntegrationTests`, `EventSubProcessInclusiveJoinIntegrationTests` | **авто** + **свой** (`NestedEventSubprocessCancelScopeIntegrationTests:81`, `EventSubProcessInclusiveJoinIntegrationTests:162`) |
| `eventSubProcess` (non-interrupting) | `:655-656` | `EventTrigger:418` | `EventSubProcessInclusiveJoinIntegrationTests`, C8-35-семья | **авто** + **свой** |
| `adHocSubProcess` | `:375-382`, `toAdHocSubProcessElement:1363-1431` | `AdHocSubProcessHandler` (`:61`) | `AdHocSubProcessIntegrationTests`, `AdHocJobWorkerIntegrationTests`, `AdHocScopeTokenIntegrationTests`, `AdhocScopeArrivalCleanupIntegrationTests`, `AdHocCompleteEndpointIT` | **авто** + **свой** (`AdHocSubProcessIntegrationTests:144`, `AdHocJobWorkerIntegrationTests:132`, `AdHocScopeTokenIntegrationTests:88`) |
| `boundaryEvent` + `timer` | `:386`, `:394-396` (тихий дроп прочих), `:607-620` | `BoundaryScheduler.scheduleBoundaryTimers:95`; re-arm `EventTrigger:368` | `ServiceTaskBoundaryTimerIntegrationTests/PgIT`, `ContainerBoundaryTimerIntegrationTests`, `SubprocessBoundaryTimerIntegrationTests`, `ReceiveTaskBoundaryIntegrationTests`, `ForkBoundaryScopeIntegrationTests`, `BoundaryCycleIntegrationTests`, `RepeatingBoundaryTimerPgIT`, `ArmedBoundaryTimerJoinPgIT`, `MiBoundaryTimerPgIT` | **авто** + **свой** (`ArmedBoundaryTimerJoinPgIT:122` — `countOf(pi,"taskNotify",CREATED)).isZero()`, `RepeatingBoundaryTimerPgIT:156`) |
| `boundaryEvent` + `error` | `:621-624` | `ErrorEscalationThrower.findErrorBoundary:254-279` | `ErrorBoundaryIntegrationTests`, `MiSubProcessIntegrationTests` | **авто**; своего ассерта на остатки в `ErrorBoundaryIntegrationTests` нет (0 absence-ассертов) |
| `boundaryEvent` + `message` | `:625-627`, def `:400-406` | `BoundaryScheduler.scheduleMessageBoundaries:60` | `MessageBoundaryIntegrationTests`, `ContainerSubscriptionCloseIntegrationTests` | **авто** + **свой** (`ContainerSubscriptionCloseIntegrationTests:84`) |
| `boundaryEvent` + `signal` | `:628-630` | `BoundaryScheduler.scheduleSignalBoundaries:31`; broadcast `EventTrigger:711` | `SignalStartBoundaryIntegrationTests`, `SignalFanOutIntegrationTests`, `MiBoundaryTimerPgIT` | **авто** |
| `boundaryEvent` + `escalation` | `:631-634` | `ErrorEscalationThrower.findEscalationBoundary:505-512` | `EscalationIntegrationTests` | **авто** |
| `boundaryEvent` + `conditional` | `:635-638`, def `:561-571` | `EventTrigger:84` | `ConditionalEventIntegrationTests` | **авто** |
| `boundaryEvent` + `compensate` | `:639-642`, association `:412-422`, handler `:2414-2424` | компенсационная механика scope-фильтра | `CompensationIntegrationTests`, `CompensationWaitIntegrationTests`, `CompensationScopeIntegrationTests` | **авто** + **свой** (`CompensationWaitIntegrationTests:96`) |
| `boundaryEvent` + `cancel` | `:643-645` | `ErrorEscalationThrower` (cancel-путь) | `ForkCancelInTransactionIntegrationTests` | **авто** + **свой** |
| `boundaryEvent` без исполняемого event-definition | `:394-396` — **тихий дроп** | — | §4-1 (тест-стража нет) | **нет** |

---

## 3. ПРИНИМАЕТСЯ: свойства элементов

| Свойство | Парсинг (file:line) | Runtime-потребитель | Основные тесты | G-P |
|---|---|---|---|---|
| `userTask`: assignee / candidateUsers / candidateGroups | `BpmnParseServiceImpl.java:2107-2111` | `UserTaskHandler` + scope-резолв MI | `UserTaskQueryIntegrationTests`, `MiSubProcessLoopVarVisibilityIntegrationTests` | **авто** + **свой** (`MiSubProcessLoopVarVisibilityIntegrationTests:293`) |
| `userTask`: dueDate / followUpDate | `:2112-2116` | планировщик задач | `UserTaskQueryIntegrationTests` | **авто** |
| `userTask`: priorityDefinition | `:2117-2123` | очередь задач | `Camunda8ParityCharacterizationTests` | **авто** |
| `userTask`: formDefinition (formKey/externalReference/formId/bindingType/versionTag) | `:2124-2147` | форма задачи | `Camunda8ParityCharacterizationTests`, `Camunda8FormsIntegrationTests`, `Api1FormValidatorIT` | н/д (вне runtime) |
| `userTask`: taskListeners (creating/completing/assigning/updating/canceling) | `:2153-2196` | `ElementSupport` | `Camunda8ParityCharacterizationTests`, `CompleteUserTaskCharacterizationPgIT`, `ListenerDuplicateCompletionTests` | **авто** + **свой** (`ListenerDuplicateCompletionTests:107,:365`) |
| `serviceTask`: `zeebe:taskDefinition` (type/retries) | `:1835-1864` (`attachServiceTaskJob`) | `ServiceTaskHandler` | `ServiceTaskFailureIntegrationTests`, `ServiceTaskJobValidationIntegrationTests` | **авто** |
| `serviceTask`: taskHeaders | `:1895-1916` (`attachTaskHeaders`) | `ServiceTaskHandler` | `Camunda8ParityCharacterizationTests` | **авто** |
| `serviceTask`: jobPriorityDefinition | `attachServiceTaskJob` + process-level default `:127-132` | `ServiceTaskHandler` | `Camunda8ParityCharacterizationTests` | **авто** |
| `serviceTask`/`sendTask`: executionListeners (start/end) | `:1761-1803` (слушатели `:1780-1802`), общий `:1866-1894` | `ElementSupport` | `Camunda8ParityCharacterizationTests`, `ListenerDuplicateCompletionTests` | **авто** + **свой** |
| MI: isSequential / loopCardinality / completionCondition | `:2208-2234` (`attachMultiInstance`) | `MultiInstanceExecutor` | `MultiInstanceIntegrationTests`, `MultiInstanceCardinalityValidationIntegrationTests`, `MiCompletionConditionPgIT`, `MiFullScanPgIT`, `MiReenterIntegrationTests`, `MiLoopReentryRacePgIT`, `MiTaskCompletionConditionSiblingsIntegrationTests` | **авто** + **свой** (`MultiInstanceCardinalityValidationIntegrationTests:113,:161,:180` — `miTasks(pi,CREATED)).isEmpty()`, `MiLoopReentryRacePgIT:175`, `MiTaskCompletionConditionSiblingsIntegrationTests:189`) |
| MI: `zeebe:loopCharacteristics` (inputCollection/inputElement/outputCollection/outputElement) | `:2221-2231` | `MultiInstanceExecutor` | `MultiInstanceOutputIntegrationTests`, `MiOutputCollectionDiffIntegrationTests`, `MiExpressionFieldDeployValidationIntegrationTests` | **авто** + **свой** (`MiOutputCollectionDiffIntegrationTests:169`) |
| MI: `inputCollection`/`outputElement` **без «=»** | — | **отказ на деплое** (§1.1 #13 / D-4) | `MiExpressionFieldDeployValidationIntegrationTests.inputCollectionWithoutEquals_isRejectedAtDeploy` (`:78`), `outputElementWithoutEquals_isRejectedAtDeploy` (`:107`) | н/д |
| MI: `inputElement`/`outputCollection` — имена, не выражения | `:2228-2229` | `MultiInstanceExecutor` | `MiExpressionFieldDeployValidationIntegrationTests.inputElementAndOutputCollection_areNamesAndAreNotRefused` (`:211`) | н/д |
| timer: timeDate / timeDuration / timeCycle (start/catch/boundary) | start `:157-166`, catch `:2337-2353`, boundary `:607-620` | планировщик таймеров | `TimerStartIntegrationTests`, `ActivityServiceImplTests`, `BoundedTimerIntegrationTest`, `RepeatingBoundaryTimerPgIT`, `RepeatingTimerSemanticsPgIT` | **авто** + **свой** |
| `sequenceFlow`: conditionExpression | `:1727-1732` (`toFlowModel:1723-1736`) | `FlowNavigator` / `InclusiveGatewayHandler.isFlowActive` | `Diff2ExclusiveGatewayOrderTest`, `Diff10GatewayNoConditionNpeTest`, `FeelDeployValidationIntegrationTests` | **авто** |
| `sequenceFlow`: defaultFlow (exclusive/inclusive) | `:2294-2298`, `:298-302` | gateway | `GatewayNoDefaultIntegrationTests`, `Diff2ExclusiveGatewayOrderTest` | **авто** |
| `incoming`/`outgoing` — нормализация из графа | `:424-428`, `:478-538` | `FlowNavigator` и все потребители | `MiSubProcessApprovalRouteIntegrationTests` (WO-C8-44), `ActivityServiceImplTests.recursionDepthGuardStopsInfiniteLoop` | **авто** |
| `callActivity`: calledElement (processId/bindingType/versionTag/propagateAll*) | `:2436-2444` | `CallActivityHandler` | `CallActivityVariablePropagationIntegrationTests`, `PropagateChildVariablesIntegrationTests` | **авто** |
| `callActivity`: ioMapping | `:2447` (`attachIoMapping`) | `ElementSupport` | `CallActivityVariablePropagationIntegrationTests`, `Diff1IoMappingScopeReproTest` | **авто** |
| ioMapping input/output (общий) | `:1917-1947` (`attachIoMapping`, `toMappings:1936-1947`), вызовы: `:752` (subProcess), `:1804` (serviceTask), `:2199` (userTask), `:2447` (callActivity) | `ElementSupport` | `IoMappingIntegrationTests`, `Eng29FeelNullIoMappingIncidentIntegrationTests`, `Eng30LenientModeNoIncidentIntegrationTests`, `Eng33NowIoMappingIntegrationTests`, `FeelIoMappingConformanceIntegrationTests`, `IoMappingStaticSourceIntegrationTests` | **авто** |
| ioMapping **output** source без «=» | — | **отказ на деплое** (D-3) | `IoMappingStaticSourceIntegrationTests` | н/д |
| ioMapping **input** source без «=» | `:220-232` валидатора | статическое значение, копируется дословно | `IoMappingStaticSourceIntegrationTests` | н/д |
| adHoc: activeElementsCollection / outputCollection / outputElement | `toAdHocSubProcessElement:1363-1431` | `AdHocSubProcessHandler` | `AdHocSubProcessIntegrationTests`, `AdHocJobWorkerIntegrationTests` | **авто** + **свой** |
| adHoc: completionCondition | там же | `AdHocSubProcessHandler` | `AdHocSubProcessIntegrationTests` | **авто** |
| adHoc: cancelRemainingInstances | там же | `AdHocSubProcessHandler` | `AdHocSubProcessIntegrationTests` | **авто** |
| conditional: conditionalFilter (variableNames/variableEvents) | `:564-571` | `ConditionalCatchHandler` / `EventTrigger` | `ConditionalEventIntegrationTests` | **авто** |
| compensate: waitForCompletion | `:578-581` | `CompensationThrowHandler:80` | `CompensationWaitIntegrationTests` | **авто** + **свой** |
| start event: formDefinition/formKey | `:177-192` | стартовая форма | `BpmnParseStartFormTest`, `Camunda8FormsIntegrationTests` | н/д |
| end/throw: job-based (`zeebe:taskDefinition`) | `:2252`, `:2406` (`attachServiceTaskJob`) | `ActivityServiceImpl.isJobBasedEvent:187-194` | `Camunda8ParityCharacterizationTests` | **авто** |
| script: `zeebe:script` (expression/resultVariable) | `:1960-1975` | `SyncTaskHandler.ScriptTask` | `ScriptTaskIntegrationTests` | **авто** |
| script: inline `<script>` | там же | `SyncTaskHandler.ScriptTask` | `ScriptTaskIntegrationTests` | **авто** |
| businessRule: `zeebe:calledDecision` (decisionId/resultVariable/bindingType/versionTag) | `:2013-2017` | DMN-вызов | `BusinessRuleTaskIntegrationTests` | **авто** |
| boundary: attachedToRef / cancelActivity (interrupting, default true) | `:602-604` | `EventTrigger.fireBoundary` | все boundary-IT | **авто** |
| `<process>`: versionTag / historyTimeToLive / jobPriorityDefinition / userTaskForm list | `:119-136` | артефакты деплоя | `Camunda8ParityCharacterizationTests` | н/д |
| `<message>`/`<error>`/`<signal>`/`<escalation>` definitions | `BpmnDefinitionsModel.java:19-30` + резолв `:94-113` | реестры событий | `ErrorBoundaryIntegrationTests`, `SignalIntegrationTests`, `EscalationIntegrationTests` | н/д |

---

## 4. Парсится/присутствует, но НЕ исполняется / тихо игнорируется / однобоко

| # | Конструкция | Факт (file:line) | Что реально происходит | Тест-страж |
|---|---|---|---|---|
| 1 | `boundaryEvent` без исполняемого event-definition (none/link/прочее) | `BpmnParseServiceImpl.java:394-396` — `continue` | **тихий дроп**: граница не попадает в модель, деплой не валится | **не найден** (стража отсутствия нет) |
| 2 | `<implicitThrowEvent>` | не в JAXB-модели (`BpmnProcessDefinitionModel.java:15-88`); в сканере — только в `FLOW_NODE_NAMES` (`:122`) для dangling-проверки | **тихо игнорируется** | `UnsupportedConstructDeployIntegrationTests.criterion6_refsToNodesTheParserDoesNotModel_areNotReportedAsDangling` (`:154`) — покрыт только как «не dangling», не как «не исполняется» |
| 3 | Choreography (`choreographyTask`/`subChoreography`/`callChoreography`) | не в JAXB; только `FLOW_NODE_NAMES:129-130` | **тихо игнорируется** | **не найдено** |
| 4 | Camunda-экспорт теги `<messageStartEvent>`/`<timerStartEvent>`/`<signalStartEvent>`/`<terminateEndEvent>` | JAXB биндит только `<startEvent>`/`<endEvent>` (`BpmnProcessDefinitionModel.java:37,:39`); сканер знает эти теги (`FLOW_NODE_NAMES:132`) | **тихо игнорируются** (модель их не видит); при этом поток из них НЕ считается dangling | `UnsupportedConstructDeployIntegrationTests.criterion6_refsToNodesTheParserDoesNotModel_areNotReportedAsDangling` (`:154`) |
| 5 | `pool` / `lane` / `collaboration` | не в `BpmnDefinitionsModel` (`:16-30`) | **тихо игнорируются** | `Camunda8ParityCharacterizationTests.poolsAndLanes_doNotAffectExecution` (`test-c8-pools.bpmn`) |
| 6 | `dataObjects` / `dataObjectReference` / `dataStore` / `itemDefinition` | не в JAXB | **тихо игнорируются** | `Camunda8ParityCharacterizationTests` (`test-c8-data-objects.bpmn`) |
| 7 | `endEvent` + `compensate` | def прикрепляется (`BpmnParseServiceImpl.java:575-581`), но **тип элемента остаётся `END_EVENT`** (`:2247-2248`), а `EndEventHandler` не читает COMPENSATE-def (в файле нет ни одного упоминания `COMPENSATE`/`Compensat`) | конец ветки = обычное завершение; **компенсация на конце не исполняется** | `Camunda8ParityCharacterizationTests` — характеризация, а не исполнение |
| 8 | `BpmnElementType.EVENT` | `BpmnElementType.java:4` — в main-коде **0 ссылок** (единственное упоминание — в тесте `ActivityServiceImplTests:1187`) | мёртвое значение enum'а | — |
| 9 | Элемент без handler'а в реестре | `ActivityServiceImpl.java:148-157` | **инцидент** «Unsupported BPMN element type» при достижении (fail-closed на runtime, не на деплое) | `ActivityServiceImplTests` |
| 10 | executionListeners на **boundary**-элементах | `attachElementStartListeners` (`:1866-1894`) вызывается для шлюзов (`:285,:304,:1617,:1632`), end (`:2254`), start (`:2281`), catch (`:2366`), throw (`:2408`); у service/user-task — свои пути (`:1798`, `:2153-2196`) — **boundary в списке нет** | **не парсятся** — слушатели на границе молча теряются | **не найдено** |
| 11 | taskListeners вне `userTask` | парсятся только для `userTask` (`:2153-2196`) | **не парсятся** | **не найдено** |
| 12 | ioMapping на `adHocSubProcess` | `attachIoMapping` вызывается для subProcess `:752`, serviceTask `:1804`, userTask `:2199`, callActivity `:2447` — **для adHoc нет** | **не парсится** — молча теряется | **не найдено** |
| 13 | MI на `transaction`/`adHocSubProcess`/`callActivity` | `BpmnSupportScanner.java:61-62` | **отказ на деплое** (не «молча один раз») | §1.1 #4 |
| 14 | MI на `task`/`scriptTask`/`manualTask`/`businessRuleTask`/`sendTask`/`receiveTask` | `:74-75` | **отказ на деплое** | §1.1 #5 |
| 15 | Обращения `<messageStartEvent>`/`<terminateEndEvent>` как «исполняемые» | `:132` — только для dangling-проверки | §4-4 | §4-4 |

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
| MI `inputCollection` / `outputElement` | **обязаны** начинаться с `=` (флаг исходного написания `:2224,:2230`); без `=` → отказ | `:262-293` | `MiExpressionFieldDeployValidationIntegrationTests` (`:78,:107,:143`) |
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

- **384 файла** BPMN: 382 в `zorrobpm-engine/src/test/files/` + 2 в `zorrobpm-engine/src/test/files/integration/`
  (имена `process1.bpmn`/`process2.bpmn` встречаются в обоих каталогах, содержимое различается — мапа
  строилась по **полному относительному пути**, чтобы не схлопывать пару). Мапа «фикстура → тест-классы»
  построена grep'ом имени файла по `zorrobpm-engine/src/test/java` + `zorrobpm-rest/src/test/java`.
- **383 из 384** фикстур упомянуты хотя бы одним тест-классом; **единственная без ссылки** —
  `process0.bpmn` (тест на неё в дереве не найден).
- Формы написания, встречающиеся **только внутри тестов**, а не фикстурами:
  `<loopCharacteristics xsi:type=…>` (табличный тест ENG-34, inline-XML),
  `standardLoopCharacteristics` на всех видах хоста (табличный тест, inline-XML).

Распределение фикстур по видам элемента (grep по тегу в XML):

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
| L-5 | **Дефектные тесты модуля `zorrobpm-rest`** | расширение живёт только в engine | §7.1 |
| L-6 | **Задания/джобы в очереди** (`service_tasks` без activity-строки) | не проверяется | — |
| L-7 | **MI-маркеры `_mi_batch_*`** | сознательно исключены из проверки scope-переменных | `LeakInvariantChecker.java:196,:208` |

### 7.3 Режим: `report` по умолчанию, `enforce` нигде не включён

- `LeakInvariantExtension.mode()` (`:204-206`): по умолчанию **`report`** — нарушения пишутся в
  `<user.dir>/target/leak-invariant-report.txt` (`:407-419`), **тест не падает**.
- `enforce` (`-Dzbpm.leakInvariant=enforce`, `:194-201`) **не включён ни в одном pom.xml, ни в `ci/`, ни в
  `.gitlab-ci.yml`** — проверено grep'ом по всему репозиторию (0 совпадений вне самого расширения).
- Allowlist (`leak-invariant-allowlist.txt`) **пуста** — известных-принятых нарушений нет (3 строки, из них
  2 — комментарии).

**Следствие для кампании:** «G-P: авто» означает «проверка выполнена и её результат записан в отчёт», а не
«тест упал бы при утечке». Единственный вид доказательства, который краснеет, — **собственный** ассерт
теста на отсутствие остатков. Поэтому в §2/§3 колонка «свой» — это то, что реально защищает от регрессии;
«авто» — детектор, который надо уметь читать (`target/leak-invariant-report.txt`).

---

## 8. Пустые/односторонние места — сводка (кандидаты ФАЗ 1–5)

| # | Место | Что пусто | Кандидат |
|---|---|---|---|
| P-1 | §4-1 | `boundaryEvent` без event-definition — **тихий дроп, стража нет** | ФАЗА 4 (события) |
| P-2 | §4-3, §4-6 | choreography / dataObjects — тихо игнорируются, стража нет (для dataObjects — есть) | ФАЗА 3/5 |
| P-3 | §4-7 | `endEvent` + `compensate` — **компенсация не исполняется** | ФАЗА 4 |
| P-4 | §4-10 | executionListeners на boundary — **не парсятся** | ФАЗА 4 |
| P-5 | §4-11 | taskListeners вне userTask — **не парсятся** | ФАЗА 1 |
| P-6 | §4-12 | ioMapping на adHoc — **не парсится** | ФАЗА 3 |
| P-7 | §1.1 #9 vs §4-4 | Camunda-экспорт теги (`<messageStartEvent>` и т.п.) **молча выпадают** из модели | ФАЗА 4 |
| P-8 | §2 | `exclusiveGateway` — своего ассерта «после XOR ничего не висло» нет | ФАЗА 2 |
| P-9 | §2 | `eventBasedGateway`, `endEvent`+escalation, `endEvent`+error, `callActivity` — своих ассертов на остатки нет | ФАЗА 1/2 |
| P-10 | §2 | `boundaryEvent` + error / signal / escalation / conditional — своих ассертов нет | ФАЗА 4 |
| P-11 | §7.2 L-1 | блок «токены с `pending_branches > 0`» **выключен** | ФАЗА 5 (сквозные) |
| P-12 | §7.2 L-2 | зависший (не завершённый) инстанс **не виден** инварианту | ФАЗА 5 |
| P-13 | §7.1 | тесты `zorrobpm-rest` (159 Spring-классов) **не покрыты** инвариантом | ФАЗА 5 |
| P-14 | §7.3 | `enforce` **не включён** нигде — инвариант не роняет тесты | ФАЗА 5 (или отдельный WO) |
| P-15 | §6 | `process0.bpmn` — фикстура без ссылки ни на один тест | ФАЗА 1 |
| P-16 | §4-8 | `BpmnElementType.EVENT` — мёртвое значение enum'а | мелочь (V7) |

---

## 9. Методология и оговорки

1. Каждая строка §1–§5 — это чтение файла на ветке `fix/ops-28-phase0` (master `87bdda7e4`) плюс grep
   по дереву. Строки, которые не удалось подтвердить по диску, помечены «не найдено» / «не знаю» —
   **домысливать нельзя** (P-1).
2. «G-P: свой» = найдена строка ассерта на **отсутствие** остатков (`isEmpty()`/`isZero()`/`isEqualTo(0)`/
   `doesNotContain` по `ActivityStatus.CREATED|IN_PROGRESS`, живым activity, токенам, таймерам, подпискам,
   arrival-строкам, `_mi_batch_*`). Проверка «активность ЕСТЬ `CREATED`» (`isEqualTo(CREATED)`) — это
   **положительный** ассерт, остатком он не является: так, например, устроен `ConditionalEventIntegrationTests`
   (`:113` и далее) и `Camunda8ParityCharacterizationTests:320`.
3. Карта «фикстура → тест» построена grep'ом имени файла по `src/test/java` (engine + rest); один файл может
   покрываться несколькими классами — в таблицах названы главные, не все.
4. «авто» в колонке G-P — НЕ «тест упал бы при утечке»: см. §7.3 (режим `report`, `enforce` не включён).
5. Границы фазы 0: не проверялась **семантика** FEEL-выражений (только факт парсинга и факт деплой-валидации),
   не проверялся runtime-путь AMQP-воркеров (только факт enqueue/handler'а), не проверялись REST-эндпоинты
   `ProcessInstance*Resource` (вне BPMN-деплоя), не снят прогон с Oracle Zeebe 8.5.0 — это ФАЗЫ 1–5
   (по WO-OPS-27).
6. **Код не менялся.** Правка только в `docs/analysis/bpmn-conformance-inventory.md` и отчёте
   `docs/internal`.
