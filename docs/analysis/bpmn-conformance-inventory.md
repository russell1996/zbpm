# BPMN Conformance Inventory — WO-OPS-28, ФАЗА 0 (read-only)

- **Дата:** 2026-10-14
- **Ветка:** `docs/ops-28-phase0-inventory` от master `cce258385`
- **Режим:** read-only анализ кода; правки кода/тестов/фикстур НЕ вносились.
- **Метод:** только факт с диска. Источники: `BpmnSupportScanner.java`, `BpmnParseServiceImpl.java`,
  `BpmnProcessDefinitionModel.java` (JAXB), `BpmnElementType.java`, `handler/*.java`,
  `HandlerRegistry.java`, `ActivityServiceImpl.java`, `BoundaryScheduler.java`, `EventTrigger.java`,
  фикстуры `zorrobpm-engine/src/test/files/*.bpmn` (340 файлов) и engine/rest-тесты, ссылающиеся на них.
- **Колонки таблицы:** элемент/свойство | принимается/отвергается (file:line) | runtime-обработка (handler,
  file:line) | тесты (имена классов, по ссылке фикстура→тест) | проверяют ли «что осталось после» —
  инвариант утечек G-P: нет activity CREATED/IN_PROGRESS, нет активных токенов, нет таймеров/подписок/job'ов,
  нет висящих MI-маркеров (`_mi_batch_*`, arrived, scope). Значения: **да** (приведена строка с проверкой),
  **нет** (читал — проверяется только финальный статус), **не знаю** (класс не читал построчно).

---

## 1. ОТВЕРГАЕТСЯ при деплое (явная 400 / исключение)

| # | Конструкция | Отказ (file:line) | Код ошибки | Тесты | G-P |
|---|---|---|---|---|---|
| 1 | Несколько `<process>` в одном ресурсе | `BpmnSupportScanner.java:137-143` | `MULTIPLE_PROCESSES_IN_RESOURCE` (`:621`) | `UnsupportedConstructDeployIntegrationTests` (criterion2), `UnsupportedConstructDeployRejectionIT` (rest) | н/д (деплой не дошёл до исполнения) |
| 2 | `<complexGateway>` | `BpmnSupportScanner.java:145-156` | `UNSUPPORTED_COMPLEX_GATEWAY` (`:622`) | `UnsupportedConstructDeployIntegrationTests` (criterion3), `UnsupportedConstructDeployRejectionIT` | н/д |
| 3 | `standardLoopCharacteristics` | `BpmnSupportScanner.java:163-171` | `UNSUPPORTED_STANDARD_LOOP` (`:623`) | `UnsupportedConstructDeployIntegrationTests` (criterion4), `LoopCharacteristicsSpellingDeployTests` | н/д |
| 4 | MI на `transaction` / `adHocSubProcess` / `callActivity` | `BpmnSupportScanner.java:177-187` + список `:61-62` | `UNSUPPORTED_MULTI_INSTANCE_ON_CONTAINER` (`:624`) | `UnsupportedConstructDeployIntegrationTests` (criterion5), `MultiInstanceOnContainersParseTest` | н/д |
| 5 | MI на `task/scriptTask/manualTask/businessRuleTask/sendTask/receiveTask` (readable-нотация) | `BpmnSupportScanner.java:192-202` + список `:74-75` | `UNSUPPORTED_MULTI_INSTANCE_ON_TASK` (`:631`) | `UnsupportedConstructDeployIntegrationTests` (criterion6), `MultiInstanceOnContainersParseTest` | н/д |
| 6 | MI как `<loopCharacteristics xsi:type="…">` (22 комбинации хост×тип) | `BpmnSupportScanner.java:204-220` | `UNSUPPORTED_MULTI_INSTANCE_XSI_TYPE` (`:637`) | `UnsupportedConstructDeployIntegrationTests` (criterion7, табличный 22×2), `LoopCharacteristicsSpellingDeployTests` | н/д |
| 7 | Conditional start event | `BpmnSupportScanner.java:222-232` | `UNSUPPORTED_CONDITIONAL_START_EVENT` (`:638`) | `UnsupportedConstructDeployIntegrationTests`, `UnsupportedConstructDeployRejectionIT` | н/д |
| 8 | Sequence flow с `sourceRef`/`targetRef` на несуществующий элемент | `BpmnSupportScanner.java:236-243` | `UNRESOLVED_SEQUENCE_FLOW_REF` (`:639`) | `UnsupportedConstructDeployIntegrationTests` | н/д |
| 9 | Service task без `zeebe:taskDefinition` (тип не выводится из id) | `ProcessDefinitionServiceImpl.java:101-122` | `SERVICE_TASK_MISSING_JOB` (400, `:117-118`) | `ServiceTaskJobValidationIntegrationTests`, `UnsupportedConstructDeployIntegrationTests` | н/д |
| 10 | Нет ни одного start event | `BpmnParseServiceImpl.java:1427-1430` (вызов `:77`) | `BpmnParseException("No start events…")` | `BpmnParseServiceImplTest` | н/д |
| 11 | >1 plain start event | `BpmnParseServiceImpl.java:1443-1445` | `BpmnParseException("More than one…")` | `BpmnParseServiceImplTest` | н/д |
| 12 | Нет ни одного end event | `BpmnParseServiceImpl.java:1446-1448` | `BpmnParseException("No end events…")` | `BpmnParseServiceImplTest` | н/д |
| 13 | Ad-hoc subProcess со start event внутри | `BpmnParseServiceImpl.java:1064-1067` | `BpmnParseException` | `BpmnParseServiceImplTest` | н/д |

Механизм отказа 1–8: `BpmnParseServiceImpl.parse` вызывает сканер (`:430`), findings попадают в
`model.getUnsupportedConstructs()`, деплой отклоняется 400 в `ProcessDefinitionServiceImpl.java:134-153`
(первая находка — в ответ, полный список — в лог).

---

## 2. ПРИНИМАЕТСЯ: flow nodes

Общий путь: JAXB-бинд (`BpmnProcessDefinitionModel.java:37-86`) → `BpmnParseServiceImpl.parse`
(`:70-430`) → модель → runtime-диспатч `ActivityServiceImpl.execute` (`ActivityServiceImpl.java:123-173`;
нет элемента → `IllegalStateException` `:124-126`; нет handler'а → инцидент «target not found» `:148-157`).
Алиасы типов: `HandlerRegistry.java:60-66` (`MESSAGE/TIMER/SIGNAL_START_EVENT→START_EVENT` `:61-63`,
`LINK_CATCH_EVENT→START_EVENT` `:64`, `RECEIVE_TASK→MESSAGE_CATCH_EVENT` `:65`).

| Элемент | Парсинг (file:line) | Runtime (file:line) | Тесты | G-P |
|---|---|---|---|---|
| `startEvent` (none) | `BpmnParseServiceImpl.java:172-174, :1918-1944` | `StartThrowEventHandler.java:29` | `Process1IntegrationTests`…`Process8IntegrationTests` (process1–8.bpmn), `BpmnStructureCoverageTest` (test-structure-coverage.bpmn), `BpmnStructureIntegrationTests` | не знаю |
| `startEvent` + message | `:143-151, :1931-1932`; `<message>`+`zeebe:subscription` `:81-92` | `StartThrowEventHandler` (алиас) + подписки | `MessageStartIntegrationTests`, `MessageSubscriptionFanOutIntegrationTests` (test-message.bpmn), `MessageCorrelationKeyIntegrationTests` (test-message-correlation-key.bpmn) | не знаю |
| `startEvent` + timer | `:152-168` (DATE `:157-159`, DURATION `:160-162`, CYCLE `:163-165`), `:1933-1934` | `StartThrowEventHandler` (алиас) + таймер-планировщик | `TimerStartIntegrationTests`, `TimerStartCycleIntegrationTests`, `RepeatingTimerSemanticsPgIT` | `RepeatingTimerSemanticsPgIT` — **да** (live-ветки); остальные — не знаю |
| `startEvent` + signal | `:1935-1936`, registry сигнала `:95-112` | `StartThrowEventHandler` (алиас) | `SignalStartBoundaryIntegrationTests` (test-signal-boundary.bpmn) | не знаю |
| `startEvent` + conditional | **отвергается** (§1, стр. 7) | — | — | — |
| `endEvent` (none) | `BpmnParseServiceImpl.java:195-201, :1895-1908` | `EndEventHandler.EndEvent.java:36` | `Process1IntegrationTests`…, `NoneEndEventPgIT` (test-none-end.bpmn, test-parallel-end.bpmn) | `NoneEndEventPgIT` — **да** (`:28,:36,:143,:187` — инстанс не завершается, пока ветка жива) |
| `endEvent` + terminate | `:1907-1900` (тип `TERMINATE_END_EVENT` `:1899-1900`) | `EndEventHandler.TerminateEndEvent.java:87` | `TerminateEndIntegrationTests`, `TerminateScopeIntegrationTests`, `TerminateSubscopeTeardownIntegrationTests` | `TerminateSubscopeTeardownIntegrationTests` — **да** (`:114` isEmpty после teardown); `TerminateScopeIntegrationTests` — **частично** (`:67,:89` — CREATED-строки/парковка); ост. — нет |
| `endEvent` + error | `:1901-1902` | `EndEventHandler.ErrorEndEvent.java:213` | `Camunda8ParityCharacterizationTests` (test-c8-error-priority.bpmn), `ErrorEscalationThrowerTest` | нет |
| `endEvent` + escalation | `:1903-1904` | `EndEventHandler.EscalationEndEvent.java:245` | `EscalationIntegrationTests` | нет |
| `endEvent` + cancel | `:1905-1906` | `CancelEndHandler.java:49` | `ForkCancelInTransactionIntegrationTests` (test-c839-*.bpmn) | **да** (`:227` «not left dangling», `:260` «compensated via the scope filter») |
| `endEvent` + compensate | `:1907-1908` (def прикрепляется `:484-490`) | **не исполняется** — `EndEventHandler` COMPENSATE-def не читает (`EndEventHandler.java:42-75`) — см. §4 | `Camunda8ParityCharacterizationTests` (test-c8-sub-compensation.bpmn) | не знаю |
| service task | `BpmnParseServiceImpl.java:203-209, :1451-1488`, job `:1515-1535` | `ServiceTaskHandler.java:32` (через AMQP-wоркер) | `ServiceTaskFailureIntegrationTests` (test-service-task-fail.bpmn), `Process3IntegrationTests` (test3.bpmn), `ListenerDuplicateCompletionTests`/`ListenerDuplicateCompletionPgIT` (test-c8-execution-listeners*.bpmn) | нет |
| send task | `:210-216, :1699-1721` (job `:1708-1714`, message `:1715-1719`) | `SendTaskHandler.java:29` | `SendReceiveTaskIntegrationTests` (test-send-receive.bpmn) | нет |
| receive task | `:217-223, :1723-1738` (message+correlationKey `:1730-1736`) | `MessageCatchHandler.java:34` (алиас `HandlerRegistry.java:65`) | `SendReceiveTaskIntegrationTests` | нет |
| manual task | `:224-230, :1740-1748` | `SyncTaskHandler.ManualTask.java:101` | `Camunda8ParityCharacterizationTests` (test-c8-manual-task.bpmn) | не знаю |
| task (без типа) | `:231-238, :1753-1761` | `SyncTaskHandler.UndefinedTask.java:138` | `BpmnStructureCoverageTest` (test-diff9-untyped-task.bpmn), `Camunda8ParityCharacterizationTests` | не знаю |
| script task | `:239-245, :1628-1665` (job `:1639-1647`, `zeebe:script` `:1650-1656`, inline `:1657-1662`) | `SyncTaskHandler.ScriptTask.java:35` | `ScriptTaskIntegrationTests` (test-script-task.bpmn) | не знаю |
| businessRule task | `:246-252, :1676-1697` (calledDecision `:1685-1689`, FEEL `:1690-1692`) | `SyncTaskHandler.BusinessRuleTask.java:166` | `BusinessRuleTaskIntegrationTests` (test-business-rule-dmn.bpmn, test-business-rule-feel.bpmn) | не знаю |
| user task | `:253-259, :1763-1868` (assignment `:1773-1777`, schedule `:1778-1781`, priority `:1785-1789`, форма `:1791-1812`, taskListeners `:1819-1863`, ioMapping `:1865`, MI `:1866`) | `UserTaskHandler.java:31` | `UserTaskQueryIntegrationTests` (test-usertask-query.bpmn), `CompleteUserTaskCharacterizationPgIT`, `Process2IntegrationTests` (test2.bpmn), `Camunda8ParityCharacterizationTests` (test-c8-user-task-form.bpmn) | не знаю |
| exclusive gateway | `:260-267, :1947-1962` (defaultFlow `:1954-1958`) | `ExclusiveGatewayHandler.java:29` | `Diff2ExclusiveGatewayOrderTest` (test-diff2-gateway-order.bpmn), `ExclusiveGatewayUnsupportedShapeIntegrationTests` (test-exclusive-gateway-degenerate*.bpmn), `GatewayNoDefaultIntegrationTests` (test-gateway-no-default.bpmn), C8-35-семья (test-c835-*.bpmn) | **частично** — в C8-35-фикстурах XOR-сплит стоит выше по потоку, и ассерты live-веток (`InclusiveJoinReadinessIntegrationTests:315,:374,:420,:489-492`, `InclusiveJoinWithoutExpectedIntegrationTests:107,:133`) через него проходят, но проверяют join, а не сам шлюз; собственного IT «после XOR ничего не висло» — нет |
| parallel gateway | `:268-275, :1964-1974` | `ParallelGatewayHandler.java:30` | `ImplicitForkPendingBranchesIntegrationTests` (test-implicit-fork-*.bpmn), `ParallelGatewayDegenerateIntegrationTests` (test-c834-pgw11.bpmn), `NoneEndEventPgIT` (test-parallel-end.bpmn) | **да** — `ImplicitForkPendingBranchesIntegrationTests:74-85` (инстанс RUNNING, пока ветка жива; токенов нет лишних) |
| event-based gateway | `:276-289, :1313-1327` | `EventBasedGatewayHandler.java:26` | `EventBasedGatewayIntegrationTests` (test-event-based-gateway.bpmn) | нет |
| inclusive gateway | `:290-308` (defaultFlow `:298-302`), `:1293-1312` | `InclusiveGatewayHandler.java:35` | `InclusiveGatewayIntegrationTests` (test-inclusive-gateway.bpmn), `InclusiveJoinReadinessMatrixIntegrationTests` (матрица 288/240/48), `InclusiveGatewayResumeOrderTest`, `InclusiveGatewayResumeTokenResolutionTest`, `InclusiveJoinReadinessRuleTest` | **да** — `InclusiveJoinReadinessIntegrationTests` (live=3+ mixed rows), `InclusiveJoinWithoutExpectedIntegrationTests:107,:133`, `InclusiveJoinDeactivationWakeupIntegrationTests:126` |
| intermediate catch + message | `:315-323, :2015-2024` | `MessageCatchHandler.java:34` | `Camunda8ParityCharacterizationTests` (test-c8-el-message-catch.bpmn), `MessageSubscriptionFanOutIntegrationTests` | не знаю |
| intermediate catch + timer | `:1997-2013` | `TimerCatchHandler.java:35` | `ActivityServiceImplTests` (test-catch.bpmn), `FeelTimerPgIT` (test-catch-feel-duration/missing-var), `BoundedTimerIntegrationTest` (test-timer-bounded.bpmn) | не знаю |
| intermediate catch + signal | `:1983-1995` (тип) | `SignalCatchHandler.java:30` | `SignalIntegrationTests` (test-signal.bpmn) | нет |
| intermediate catch + link | `:481-483` (def) | `StartThrowEventHandler` (алиас `HandlerRegistry.java:64`) | `LinkEventIntegrationTests` (test-link-events.bpmn) | нет |
| intermediate catch + conditional | `:470-480` (filter `:475-480`) | `ConditionalCatchHandler.java:35` | `ConditionalEventIntegrationTests` (test-conditional-catch*.bpmn, test-conditional-boundary.bpmn) | нет |
| intermediate catch (bare, без event-def) | `:315-323` (без def) | `WaitStateHandler.java:26` | `ActivityServiceImplTests` (характеризация) | не знаю |
| intermediate throw + message | `:325-334, :2038-2044` | `MessageThrowHandler.java:34` | `ActivityServiceImplTests` (test-message-throw.bpmn) | не знаю |
| intermediate throw + signal | `:2045-2046` | `SignalThrowHandler.java:31` | `SignalIntegrationTests` (test-signal.bpmn) | нет |
| intermediate throw + escalation | `:2047-2048` | `StartThrowEventHandler.StartEscalationThrow.java:51` | `EscalationIntegrationTests` (test-escalation-throw.bpmn) | нет |
| intermediate throw + link | `:2049-2050` | `LinkThrowHandler.java:31` | `LinkEventIntegrationTests` (test-link-events.bpmn) | нет |
| intermediate throw + compensate | `:2051-2061` (waitForCompletion `:490`) | `CompensationThrowHandler.java:40` (read `:80`) | `CompensationIntegrationTests` (test-compensation.bpmn, test-compensation-targeted.bpmn) | не знаю |
| intermediate throw (bare) | `:2062-2063` | `IntermediateThrowEventHandler.java:27` | `ActivityServiceImplTests` (test-throw.bpmn) | не знаю |
| call activity | `:336-342, :2086-2110` (calledElement `:2096-2104`, ioMapping `:2107`) | `CallActivityHandler.java:40` | `CallActivityVariablePropagationIntegrationTests` (test-c8-call-*.bpmn, test-eng11-*.bpmn), `PropagateChildVariablesIntegrationTests`, `CallActivityNullSafetyIntegrationTests`, `CallActivityRecursionIntegrationTests` | нет |
| subProcess (embedded) | `:344-359`, `toSubProcessElement :560-638` (flatten `:625`, MI `:632`) | `SubProcessHandler.java:35` | `SubProcessIntegrationTests` (test-subprocess.bpmn), `NestedTokenPendingBranchesIntegrationTests` (test-nested-token-*.bpmn), `Diff1IoMappingScopeReproTest` | `NestedTokenPendingBranchesIntegrationTests` — **да** (live=2); `SubProcessIntegrationTests` — нет |
| subProcess + MI | `:560-638` (MI `:632`; хост разрешён — в `MULTI_INSTANCE_UNSUPPORTED_HOSTS` `BpmnSupportScanner.java:61-62` его нет) | `MultiInstanceExecutor` (создание копий/слотов) | `MiSubProcessIntegrationTests` (test-c840-*.bpmn), `MiSubProcessMinimalPgIT`, `MiSubProcessRaceIntegrationTests`, `MiSubProcessApprovalRouteIntegrationTests` | **да** — `MiSubProcessIntegrationTests:457-460,:491,:524-566,:591-596` («no live activities left»), `MiSubProcessCamunda8ParityIntegrationTests:431,:528` (jobs отменены, siblings завершены), `MiSubProcessLoopVarVisibilityIntegrationTests:283,:377,:479` (нет stale root rows), `MiSubProcessApprovalRouteIntegrationTests:227-229` (остатки CREATED) |
| subProcess + MI + Camunda8-parity | фикстуры `test-c841-parity-*.bpmn` | `MultiInstanceExecutor` | `MiSubProcessCamunda8ParityIntegrationTests` (11–12 тестов) | **да** (`:431,:528`) |
| subProcess + MI + loop-variable/reentry | фикстуры `test-c842-*.bpmn` | `MultiInstanceExecutor` + scope-chain | `MiSubProcessLoopVarVisibilityIntegrationTests` | **да** (`:283,:377,:479`) |
| transaction | `:361-371` (парсится как SUB_PROCESS; `xsi`-тип игнорируется) | `SubProcessHandler` + cancel-end внутри | `TransactionCancelIntegrationTests` (test-transaction-cancel.bpmn), `ForkCancelInTransactionIntegrationTests` | `ForkCancelInTransactionIntegrationTests` — **да** (`:227,:260`); `TransactionCancelIntegrationTests` — нет |
| eventSubProcess (interrupting) | `:560-565` (flag `:564-565`), start `:577-613`, end `:615-624` | **нет registry-handler'а** — срабатывает через `EventTrigger.triggerEventSubprocess.java:413`; подписка `:617`; error-триггер `ErrorEscalationThrower.java:167` | `EventSubProcessIntegrationTests` (test-event-subprocess*.bpmn), `NestedEventSubprocessCancelScopeIntegrationTests` | **да** — `NestedEventSubprocessCancelScopeIntegrationTests` (live=1 после cancel scope); `EventSubProcessIntegrationTests` — нет |
| eventSubProcess (non-interrupting) | `:584` (isInterrupting) | `EventTrigger.java:413` | `EventSubProcessInclusiveJoinIntegrationTests` (live=2), C8-35-семья (test-c835-*.bpmn) | **да** — `EventSubProcessInclusiveJoinIntegrationTests` (armed-триггеры + join) |
| adHoc subProcess | `:373-382`, `:1053-1111` (start запрещён `:1064-1067`, adhoc-ext `:1087-1094`, completionCondition `:1095-1097`, cancelRemaining `:1098`, job `:1109`) | `AdHocSubProcessHandler.java:61` | `AdHocSubProcessIntegrationTests` (test-adhoc-*.bpmn), `AdHocJobWorkerIntegrationTests`, `AdHocCompleteEndpointIT`, `AdHocScopeTokenIntegrationTests` (test-c837-*.bpmn) | **да** — `AdHocJobWorkerIntegrationTests` (CREATED isEmpty ×5), `AdHocSubProcessIntegrationTests:194` (нет CREATED для невалидного id); C8-37-семья — **да** (scope-token) |
| boundaryEvent + timer | `:384-409` (exec-фильтр `:394-396`), `toBoundaryElement :500-558` (attachedToRef `:511`, interrupting `:512-513`, timer `:516-529`) | `BoundaryScheduler.scheduleBoundaryTimers.java:95`; re-arm `EventTrigger.java:362` | `ActivityServiceImplTests` (test-boundary*.bpmn), `ServiceTaskBoundaryTimerIntegrationTests`/`PgIT`, `ContainerBoundaryTimerIntegrationTests`, `SubprocessBoundaryTimerIntegrationTests`, `ReceiveTaskBoundaryIntegrationTests`, `ForkBoundaryScopeIntegrationTests`, `BoundaryCycleIntegrationTests`, `RepeatingBoundaryTimerPgIT`, `BoundedTimerIntegrationTest`, `FeelTimerPgIT`, `MiBoundaryTimerPgIT` | **да** — `BoundaryCycleIntegrationTests:153` (host CREATED после re-fire; таймеры не растут), `RepeatingBoundaryTimerPgIT:31-43` (остаток persisted-count), `ArmedBoundaryTimerJoinPgIT` (join ждёт взведённый таймер) |
| boundaryEvent + error | `:530-533` | `ErrorEscalationThrower` (error-путь) | `ErrorBoundaryIntegrationTests` (test-error-boundary.bpmn), `MiSubProcessIntegrationTests` (test-c840-mi-sub-error-boundary.bpmn) | `ErrorBoundaryIntegrationTests` — нет; MI-вариант — **да** (через MiSubProcessIntegrationTests) |
| boundaryEvent + message | `:534-536`, ext `:400-406` | `BoundaryScheduler.scheduleMessageBoundaries.java:60` | `MessageBoundaryIntegrationTests` (test-message-boundary.bpmn), `ContainerSubscriptionCloseIntegrationTests` | **да** — `ContainerSubscriptionCloseIntegrationTests:96-130` (подписка закрыта/потреблена после срабатывания) |
| boundaryEvent + signal | `:537-539` | `BoundaryScheduler.scheduleSignalBoundaries.java:31`; broadcast `EventTrigger.java:650` | `SignalStartBoundaryIntegrationTests` (test-signal-boundary.bpmn), `SignalFanOutIntegrationTests` (test-rel31-signal-fanout.bpmn), `MiBoundaryTimerPgIT` (test-mi-boundary-signal.bpmn) | `SignalFanOutIntegrationTests` — не знаю; ост. — нет |
| boundaryEvent + escalation | `:540-543` | `ErrorEscalationThrower` (escalation-путь) | `EscalationIntegrationTests` (test-escalation-interrupting/noninterrupting.bpmn) | нет |
| boundaryEvent + conditional | `:544-547`, filter `:475-480` | `EventTrigger` (conditional-путь) | `ConditionalEventIntegrationTests` (test-conditional-boundary.bpmn) | нет |
| boundaryEvent + compensate | `:548-551`, association `:411-422`, `:1395-1405`, resolve `:2074-2084` | compensation-mеханика scope-фильтра (`ElementSupport.java:1663`) | `CompensationIntegrationTests` (test-compensation*.bpmn), `CompensationWaitIntegrationTests`, `CompensationScopeIntegrationTests`, `ForkCancelInTransactionIntegrationTests:244-260` | **да** — `ForkCancelInTransactionIntegrationTests:244-260` (compensated via scope filter); ост. — не знаю |
| boundaryEvent + cancel | `:552-554` | `ErrorEscalationThrower` (cancel-путь) | `ForkCancelInTransactionIntegrationTests` (test-c839-*.bpmn) | **да** (`:227,:260`) |
| boundaryEvent на MI-контейнере | `:384-409` | `EventTrigger.fireBoundary.java:103` (scope-отмена) | `MiBoundaryTimerPgIT`, `MiSubProcessIntegrationTests` | **да** (через MiSubProcessIntegrationTests: `:457-460`) |
| `<process>`: versionTag | `:120-123` | — (матчинг версий) | `Camunda8ParityCharacterizationTests` (test-c8-version-tag.bpmn, test-c8-binding-*.bpmn) | н/д |
| `<process>`: historyTimeToLive | `:124-126` | retention (вне runtime) | `Camunda8ParityCharacterizationTests` | н/д |
| `<process>`: jobPriorityDefinition | `:129-132` | `attachServiceTaskJob :1531-1534` | `Camunda8ParityCharacterizationTests` | н/д |
| `<message>` + correlationKey | `:81-92` | correlation в catch/receive | `MessageCorrelationKeyIntegrationTests` | не знаю |
| `<error>/<signal>/<escalation>` definitions | `:95-112` | — (registry) | `ErrorBoundaryIntegrationTests`, `SignalIntegrationTests`, `EscalationIntegrationTests` | н/д |

---

## 3. ПРИНИМАЕТСЯ: свойства элементов

| Свойство | Парсинг (file:line) | Runtime-потребитель | Тесты | G-P |
|---|---|---|---|---|
| userTask: assignee/candidateUsers/candidateGroups | `BpmnParseServiceImpl.java:1773-1777` | `UserTaskHandler` + MI-spawn scope-резолв | `UserTaskQueryIntegrationTests`, `MiSubProcessLoopVarVisibilityIntegrationTests` | **да** (MI-свесть — `:283,:377,:479`) |
| userTask: dueDate/followUpDate | `:1778-1781` | планировщик задач | `UserTaskQueryIntegrationTests` | не знаю |
| userTask: priorityDefinition | `:1785-1789` | очередь задач | `Camunda8ParityCharacterizationTests` | не знаю |
| userTask: formDefinition (formKey/externalReference/formId/bindingType/versionTag) | `:1791-1812` (binding `:1804`) | форма задачи | `Camunda8ParityCharacterizationTests` (test-c8-user-task-form.bpmn), `Camunda8FormsIntegrationTests`, `Api1FormValidatorIT` | н/д |
| userTask: taskListeners (creating/completing/assigning/updating/canceling) | `:1819-1863` | `ElementSupport` (listeners-цепочка) | `Camunda8ParityCharacterizationTests` (test-c8-task-listeners*.bpmn), `CompleteUserTaskCharacterizationPgIT` | не знаю |
| serviceTask: zeebe:taskDefinition (type/retries) | `:1525-1526` (через `:1515-1535`) | `ServiceTaskHandler` (AMQP) | `ServiceTaskFailureIntegrationTests`, `ServiceTaskJobValidationIntegrationTests` | нет |
| serviceTask: taskHeaders | `:1527` (`attachTaskHeaders :1575-1594`) | `ServiceTaskHandler` | `Camunda8ParityCharacterizationTests` (test-c8-task-headers*.bpmn) | не знаю |
| serviceTask: jobPriorityDefinition | `:1529-1534` | `ServiceTaskHandler` | `Camunda8ParityCharacterizationTests` | не знаю |
| serviceTask: executionListeners (start/end) | `:1462-1484` (`attachElementStartListeners :1546-1567`) | `ElementSupport` | `ListenerDuplicateCompletionTests`/`PgIT` (test-c8-execution-listeners*.bpmn), `Camunda8ParityCharacterizationTests` | не знаю |
| MI: isSequential / loopCardinality / completionCondition | `:1878-1880` (`attachMultiInstance :1873-1893`) | `MultiInstanceExecutor` | `MultiInstanceIntegrationTests` (test-multi-instance*.bpmn), `MultiInstanceCardinalityValidationIntegrationTests`, `MiCompletionConditionPgIT`, `MiFullScanPgIT`, `MiReenterIntegrationTests`, `MiLoopReentryRacePgIT`, `MiOutputPositionalPgIT`, `MiAggregationRacePgIT` | **да** — `MultiInstanceCardinalityValidationIntegrationTests:113,:161,:180` (miTasks CREATED isEmpty), `MiCompletionConditionPgIT` (live=3), `MiOutputCollectionDiffIntegrationTests` (`_mi_batch`-маркеры), `MiLoopReentryRacePgIT`; базовый `MultiInstanceIntegrationTests` — нет |
| MI: zeebe:loopCharacteristics (inputCollection/InputElement/outputCollection/outputElement) | `:1881-1888` | `MultiInstanceExecutor` | `MultiInstanceOutputIntegrationTests`, `MiOutputCollectionDiffIntegrationTests`, `MultiInstanceExecutorBatchUuidTest`, `MultiInstanceExecutorPinpointTest` | **да** — `MiOutputCollectionDiffIntegrationTests`, `MultiInstanceExecutorBatchUuidTest`/`PinpointTest` (`_mi_batch_*`-маркеры) |
| MI на task (service/user) — разрешён | не в списках `BpmnSupportScanner.java:61-62,:74-75` | `MultiInstanceExecutor` | `MultiInstanceServiceTaskIntegrationTests`, `MultiInstanceElementIntegrationTests`, `MiSubProcessIntegrationTests` | см. строки выше |
| timer: timeDate/timeDuration/timeCycle (start/catch/boundary) | start `:152-168`, catch `:1997-2013`, boundary `:516-529` | таймер-планировщик | `TimerStartIntegrationTests`, `ActivityServiceImplTests`, `BoundedTimerIntegrationTest`, `RepeatingBoundaryTimerPgIT` | **да** — `RepeatingBoundaryTimerPgIT:31-43`, `RepeatingTimerSemanticsPgIT`, `BoundaryCycleIntegrationTests:153` |
| sequenceFlow: conditionExpression | `:1418-1423` (`toFlowModel :1413-1425`) | gateway/flow-навигация (FEEL) | `Diff2ExclusiveGatewayOrderTest`, `Diff10GatewayNoConditionNpeTest` | не знаю |
| sequenceFlow: defaultFlow (exclusive/inclusive) | `:1954-1958`, `:298-302` | gateway | `GatewayNoDefaultIntegrationTests`, `Diff2ExclusiveGatewayOrderTest` | не знаю |
| callActivity: calledElement (processId/bindingType/versionTag/propagateAll*) | `:2096-2104` | `CallActivityHandler` | `CallActivityVariablePropagationIntegrationTests`, `PropagateChildVariablesIntegrationTests` | нет |
| callActivity: ioMapping | `:2107` | `ElementSupport` (ioMapping) | `CallActivityVariablePropagationIntegrationTests` | нет |
| adHoc: activeElementsCollection/outputCollection/outputElement | `:1087-1094` | `AdHocSubProcessHandler` | `AdHocSubProcessIntegrationTests`, `AdHocJobWorkerIntegrationTests` | **да** (CREATED isEmpty ×5/2) |
| adHoc: completionCondition | `:1095-1097` | `AdHocSubProcessHandler` | `AdHocSubProcessIntegrationTests` | не знаю |
| adHoc: cancelRemainingInstances | `:1098` | `AdHocSubProcessHandler` | `AdHocSubProcessIntegrationTests` | не знаю |
| conditional: conditionalFilter (variableNames/variableEvents) | `:475-480` | `ConditionalCatchHandler`/`EventTrigger` | `ConditionalEventIntegrationTests` | нет |
| compensate: waitForCompletion | `:490` | `CompensationThrowHandler.java:80` | `CompensationWaitIntegrationTests` | не знаю |
| start event: formDefinition/formKey | `:175-192` | start-форма | `BpmnParseStartFormTest`, `Camunda8FormsIntegrationTests` | н/д |
| end/throw: job-based (`zeebe:taskDefinition` на end event) | `:1912` (end), `:2066` (throw) | `ActivityServiceImpl.isJobBasedEvent.java:187-197` | `Camunda8ParityCharacterizationTests` (test-c8-job-end.bpmn, test-c8-job-throw.bpmn) | не знаю |
| ioMapping input/output (общий) | `:1597-1614` (`toMappings :1616-1626`), вызовы: service `:1485`, userTask `:1865`, subProcess `:354`, callActivity `:2107` | `ElementSupport` | `IoMappingIntegrationTests` (test-io-mapping.bpmn), `Eng29FeelNullIoMappingIncidentIntegrationTests`, `Eng30LenientModeNoIncidentIntegrationTests`, `Diff1IoMappingScopeReproTest`, `Eng33NowIoMappingIntegrationTests` | нет |
| script: zeebe:script (expression/resultVariable) | `:1650-1656` | `SyncTaskHandler.ScriptTask` | `ScriptTaskIntegrationTests` | не знаю |
| script: inline `<script>` | `:1657-1662` | `SyncTaskHandler.ScriptTask` | `ScriptTaskIntegrationTests` | не знаю |
| businessRule: zeebe:calledDecision (decisionId/resultVariable/bindingType/versionTag) | `:1685-1689` | DMN-вызов | `BusinessRuleTaskIntegrationTests` | не знаю |
| boundary: attachedToRef | `:511` | `EventTrigger.fireBoundary` | все boundary-IT | н/д |
| boundary: cancelActivity (interrupting, default true) | `:512-513` | `EventTrigger`/`ErrorEscalationThrower` | `EscalationIntegrationTests` (interrupting/noninterrupting) | нет |

---

## 4. Парсится/присутствует, но НЕ исполняется / тихо игнорируется / однобоко

| # | Конструкция | Факт (file:line) | Что реально происходит | Тест |
|---|---|---|---|---|
| 1 | boundaryEvent без исполняемого event-definition (none/link/прочее) | `BpmnParseServiceImpl.java:394-396` (внешний уровень), `:1375-1377` (вложенный в subProcess) | **тихий дроп**: граница молча не попадает в модель; деплой не валится | **не найдено** (тест-стража отсутствия нет) |
| 2 | `<implicitThrowEvent>` | не в JAXB-модели (`BpmnProcessDefinitionModel.java:37-86` — бинда нет); только в `BpmnSupportScanner.FLOW_NODE_NAMES:111` | **тихо игнорируется** (узел в документе, не в модели; flows к нему — dangling-проверка сканера) | **не найдено** |
| 3 | Choreography (`choreographyTask`/`subChoreography`/`callChoreography`) | не в JAXB-модели; только в `FLOW_NODE_NAMES:119` | **тихо игнорируется** | **не найдено** |
| 4 | Camunda-экспорт теги `<messageStartEvent>`/`<timerStartEvent>`/`<signalStartEvent>`/`<terminateEndEvent>` | JAXB биндит только `<startEvent>`/`<endEvent>` + definition (`BpmnProcessDefinitionModel.java:37,:41,:195-201` parse); сканер учитывает их в `FLOW_NODE_NAMES:121` только для dangling-проверки | **тихо игнорируются**: если весь start такой — «No start events» (строка §1-10); если есть и обычный — эксклюзивные молча выпадают из модели | **не найдено** (явного тест-стража нет) |
| 5 | pool/lane | не в JAXB-модели (нет `@XmlElement` на pool/lane, `BpmnProcessDefinitionModel.java:31-86`) | **тихо игнорируется** | `Camunda8ParityCharacterizationTests.poolsAndLanes_doNotAffectExecution` (`:3794`, test-c8-pools.bpmn) |
| 6 | dataObjects/dataObjectReference | не в JAXB-модели | **тихо игнорируется** | `Camunda8ParityCharacterizationTests` (test-c8-data-objects.bpmn) |
| 7 | `endEvent` + compensate | парсится, def прикрепляется (`BpmnParseServiceImpl.java:484-490`), но `EndEventHandler` COMPENSATE-def **не читает** (`EndEventHandler.java:42-75`) | конец ветки = обычное завершение; **компенсация на конце не исполняется** | `Camunda8ParityCharacterizationTests` (test-c8-sub-compensation.bpmn) — характеризация, не исполнение |
| 8 | `BpmnElementType.EVENT` | `BpmnElementType.java:4` — 0 ссылок в main-коде (grep по engine+rest) | мёртвое значение enum'а | — |
| 9 | Любой элемент без handler'а в реестре | `ActivityServiceImpl.java:148-157` | инцидент «target not found» при достижении (fail-closed на runtime, не на деплое) | `ActivityServiceImplTests` |
| 10 | executionListeners на boundary-элементах | `attachElementStartListeners` вызывается только для task/gateway/throw/end (`BpmnParseServiceImpl.java:1543-1546` + вызовы `:1462,:1819,:1914,:1961` и т.п.) | **не парсятся** — слушатели на границе молча теряются | **не найдено** |
| 11 | taskListeners вне userTask | парсится только для userTask (`:1819-1863`) | **не парсятся** для service/send/receive/прочих | **не найдено** |
| 12 | ioMapping на adHoc subProcess | `attachIoMapping` вызывается для service `:1485`, userTask `:1865`, subProcess `:354`, callActivity `:2107` — **не** для adHoc | **не парсится** — молча теряется | **не найдено** |

---

## 5. Фикстуры (`zorrobpm-engine/src/test/files/`, 340 файлов)

Сгруппированы по покрытию (полный список — `ls zorrobpm-engine/src/test/files`):

- **process1–8.bpmn / test1–8.bpmn** — базовые процессы (start/task/end, user task, service task):
  `Process1IntegrationTests`…`Process8IntegrationTests`, `BpmnStructureCoverageTest`, `BpmnStructureIntegrationTests`,
  `Camunda8ParityCharacterizationTests` (частично).
- **test-c8-*.bpmn** (~90) — Camunda8-parity characterization: tasks, gateways, events, forms, version tag,
  binding, data objects, pools, job end/throw, execution/task listeners, task headers:
  `Camunda8ParityCharacterizationTests` (главный), `Camunda8FormsIntegrationTests`.
- **test-c834-pgw11 / test-c835-*.bpmn / test-c837-*.bpmn** — inclusive-join/deactivation/ad-hoc-scope:
  `InclusiveJoin*`-семья, `InclusiveGateway*`, `ArmedBoundaryTimerJoinPgIT`, `DeadElementIdsMixedRows*`,
  `AdHocScopeTokenIntegrationTests`, `ScopeJoinCleanupWiringTest`, `DeactivationWakeupCoverageTest` (охранники).
- **test-c839-*.bpmn** — cancel-end в transaction/fork: `ForkCancelInTransactionIntegrationTests`.
- **test-c840-*.bpmn** — MI subProcess: `MiSubProcessIntegrationTests`, `MiSubProcessMinimalPgIT`,
  `MiSubProcessRaceIntegrationTests`, `MiSubProcessApprovalRouteIntegrationTests`.
- **test-c841-parity-*.bpmn** — MI×Camunda8 parity: `MiSubProcessCamunda8ParityIntegrationTests`.
- **test-c842-*.bpmn** — loop-variable/reentry: `MiSubProcessLoopVarVisibilityIntegrationTests`.
- **test-multi-instance*.bpmn / test-mi-*.bpmn** — MI на tasks: `MultiInstance*IntegrationTests`,
  `MultiInstanceCardinalityValidationIntegrationTests`, `MiFullScanPgIT`, `MiCompletionConditionPgIT`,
  `MiReenterIntegrationTests`, `MiLoopReentryRacePgIT`, `MiOutputPositionalPgIT`, `MiAggregationRacePgIT`,
  `MiOutputCollectionDiffIntegrationTests`, `MiBoundaryTimerPgIT`.
- **test-boundary*.bpmn / test-catch*.bpmn / test-timer-*.bpmn** — boundary/catch/timer:
  `ActivityServiceImplTests`, `ServiceTaskBoundaryTimerIntegrationTests/PgIT`, `ContainerBoundaryTimerIntegrationTests`,
  `SubprocessBoundaryTimerIntegrationTests`, `ReceiveTaskBoundaryIntegrationTests`, `ForkBoundaryScopeIntegrationTests`,
  `BoundaryCycleIntegrationTests`, `RepeatingBoundaryTimerPgIT`, `BoundedTimerIntegrationTest`, `FeelTimerPgIT`.
- **test-message*/test-signal*/test-link-events/test-conditional*/test-escalation*/test-compensation*/test-throw/test-signal-boundary** —
  event-семьи: `MessageBoundaryIntegrationTests`, `MessageStartIntegrationTests`, `MessageSubscriptionFanOutIntegrationTests`,
  `MessageCorrelationKeyIntegrationTests`, `SignalIntegrationTests`, `SignalStartBoundaryIntegrationTests`,
  `SignalFanOutIntegrationTests`, `LinkEventIntegrationTests`, `ConditionalEventIntegrationTests`,
  `EscalationIntegrationTests`, `CompensationIntegrationTests`, `CompensationWaitIntegrationTests`,
  `CompensationScopeIntegrationTests`.
- **test-subprocess / test-nested-token-*/test-nested-event-subprocess*/test-event-subprocess*** — контейнеры:
  `SubProcessIntegrationTests`, `NestedTokenPendingBranchesIntegrationTests`, `EventSubProcessIntegrationTests`,
  `NestedEventSubprocessCancelScopeIntegrationTests`, `EventSubProcessInclusiveJoinIntegrationTests`.
- **test-adhoc-*.bpmn** — `AdHocSubProcessIntegrationTests`, `AdHocJobWorkerIntegrationTests`, `AdHocCompleteEndpointIT`.
- **test-c8-call-*/test-eng11-*/test-propagate-*** — call activity: `CallActivityVariablePropagationIntegrationTests`,
  `PropagateChildVariablesIntegrationTests`, `CallActivityNullSafetyIntegrationTests`, `CallActivityRecursionIntegrationTests`.
- **test-transaction-cancel / test-implicit-fork-*/test-diff1-*/test-diff2-*/test-diff9-*/test-diff10-*** —
  `TransactionCancelIntegrationTests`, `ImplicitForkPendingBranchesIntegrationTests`, `Diff1IoMappingScopeReproTest`,
  `Diff2ExclusiveGatewayOrderTest`, `Diff10GatewayNoConditionNpeTest`, `BpmnStructureCoverageTest`.
- **test-inclusive-gateway / test-gateway-no-default / test-exclusive-gateway-degenerate*** — gateways:
  `InclusiveGatewayIntegrationTests`, `InclusiveJoinReadinessMatrixIntegrationTests`, `GatewayNoDefaultIntegrationTests`,
  `ExclusiveGatewayUnsupportedShapeIntegrationTests`, `EventBasedGatewayIntegrationTests`.
- **test-eng29/30/33*/test-io-mapping*/test-c8-execution-listeners*/test-c8-task-listeners*/test-c8-task-headers* / test-script-task / test-business-rule-* / test-usertask-query / test-service-task-fail / test-send-receive / test-none-end / test-multi-instance-cardinality / test-c8-job-end / test-c8-job-throw / test-c8-pools / test-c8-data-objects / test-c8-form-* / test-c8-version-tag / test-c8-binding-* / test-c8-manual-task / test-c8-untyped-task / test-c8-user-task-form** — свойства/par-итмы (см. §2–3).
- **test-eng34-*.bpmn / test-loop-*** — деплой-отказы: `UnsupportedConstructDeployIntegrationTests` (14 тестов, criterion1–7),
  `UnsupportedConstructDeployRejectionIT` (rest, 3), `LoopCharacteristicsSpellingDeployTests`, `MultiInstanceOnContainersParseTest`.
- **Деплой-механика (не BPMN-семантика):** `DeploymentAtomicityPgIT`, `ProcessDefinitionServiceAtomicityIntegrationTests`,
  `BatchDeployConcurrencyPgIT`, `ProcessDefinitionDeployRacePgIT`, `ProcessDefinitionServiceImplTest`,
  `ProcessDefinitionVersioningTest`, `BpmnParseServiceImplTest`, `BpmnParseEventsTest`, `BpmnParseStartFormTest`,
  `BpmnParseSubProcessMultiInstanceTest`, `BpmnParseServiceImplCallActivityMappingTest`, `BpmnProcessDefinitionModelJobTypesTest`,
  `BatchDeployValidationIT` (rest, 6 — size-limits, вне семантики BPMN).

---

## 6. Итог: покрытие инварианта утечек G-P

**«Да» (реальная проверка «что осталось после» — найдены строки с assert'ами):**
`MiSubProcessIntegrationTests`, `MiSubProcessCamunda8ParityIntegrationTests`, `MiSubProcessLoopVarVisibilityIntegrationTests`,
`MiSubProcessApprovalRouteIntegrationTests` (частично), `InclusiveJoinReadinessIntegrationTests`,
`InclusiveJoinWithoutExpectedIntegrationTests`, `InclusiveJoinDeactivationWakeupIntegrationTests`,
`ArmedBoundaryTimerJoinPgIT`, `DeadElementIdsMixedRowsPgIT`, `EventSubProcessInclusiveJoinIntegrationTests`,
`NestedEventSubprocessCancelScopeIntegrationTests`, `AdHocJobWorkerIntegrationTests`, `AdHocSubProcessIntegrationTests` (частично),
`MultiInstanceCardinalityValidationIntegrationTests`, `MultiInstanceExecutorBatchUuidTest`, `MultiInstanceExecutorPinpointTest`,
`MiOutputCollectionDiffIntegrationTests`, `MiLoopReentryRacePgIT`, `MiCompletionConditionPgIT`, `NoneEndEventPgIT`,
`ImplicitForkPendingBranchesIntegrationTests`, `NestedTokenPendingBranchesIntegrationTests`,
`BoundaryCycleIntegrationTests`, `RepeatingBoundaryTimerPgIT`, `RepeatingTimerSemanticsPgIT`,
`ContainerSubscriptionCloseIntegrationTests`, `TerminateSubscopeTeardownIntegrationTests`, `TerminateScopeIntegrationTests` (частично),
`ForkCancelInTransactionIntegrationTests`, `CancelledScopeArrivalCleanupIntegrationTests` (+ ExtraPaths, AdhocScopeArrivalCleanup — arrived-строки),
охранники: `ScopeJoinCleanupWiringTest`, `DeactivationWakeupCoverageTest`.

**«Нет» (читал — только финальный статус, без проверки остатков):**
`SubProcessIntegrationTests`, `EventSubProcessIntegrationTests`, `TransactionCancelIntegrationTests`,
`TerminateEndIntegrationTests`, `ErrorBoundaryIntegrationTests`, `EscalationIntegrationTests`, `InclusiveGatewayIntegrationTests`,
`CallActivityVariablePropagationIntegrationTests`, `LinkEventIntegrationTests`, `ConditionalEventIntegrationTests`,
`CompensationIntegrationTests`, `IoMappingIntegrationTests`, `MultiInstanceIntegrationTests`, `SendReceiveTaskIntegrationTests`,
`MessageBoundaryIntegrationTests`, `SignalIntegrationTests`, `EventBasedGatewayIntegrationTests`,
`ParallelGatewayDegenerateIntegrationTests`, `Process1–8IntegrationTests`.

**«Не знаю» (класс не читал построчно; для ФАЗЫ 1 — кандидаты на допроверку):**
все остальные классы §2–3, в первую очередь: `MessageStartIntegrationTests`, `TimerStartIntegrationTests`,
`ScriptTaskIntegrationTests`, `BusinessRuleTaskIntegrationTests`, `UserTaskQueryIntegrationTests`,
`CompleteUserTaskCharacterizationPgIT`, `ServiceTaskFailureIntegrationTests`, `ListenerDuplicateCompletionTests`,
`SignalFanOutIntegrationTests`, `MiSubProcessRaceIntegrationTests`, `AdHocScopeTokenIntegrationTests`,
`CompensationWaitIntegrationTests`, `CompensationScopeIntegrationTests`, `Camunda8ParityCharacterizationTests` (1500+ строк).

**Элементы/свойства БЕЗ какого-либо «да» в текущей базе (все тесты — «нет» или «не знаю»;
приоритеты ФАЗЫ 1):**
- startEvent (none), + message, + signal (у timer-старта есть «да» — `RepeatingTimerSemanticsPgIT`);
- endEvent + error, + escalation, + compensate (у compensate дополнительно §4-7 — исполнение вообще не доказано);
- все задачи: service, send, receive, manual, task (bare), script, businessRule, user (у user task
  есть «да» только у свойства assignee в MI-варианте, у самого элемента — нет);
- exclusive gateway (частично — см. таблицу), event-based gateway;
- intermediate catch: + message, + signal, + link, + conditional, bare;
- intermediate throw: все 6 вариантов (+ message, + signal, + escalation, + link, + compensate, bare);
- call activity;
- boundaryEvent: + signal (собственный IT), + escalation, + conditional;
- свойства: `<message>`/correlationKey, sequenceFlow conditionExpression/defaultFlow,
  taskListeners (userTask), executionListeners (serviceTask), ioMapping (общий),
  adHoc completionCondition/cancelRemainingInstances, timer catch (у boundary/start есть «да», у catch — нет).

## 7. Методология и оговорки

1. Каждая строка §1–4 — это grep/read по файлам на ветке `docs/ops-28-phase0-inventory` (master `cce258385`).
   Строки, которые не удалось подтвердить по диску, помечены «не найдено» / «не знаю», не домысливались.
2. «G-P: да» = в классе найдена строка assert'а на остаточное состояние (CREATED/IN_PROGRESS isEmpty, live-счётчик,
   timer/subscription/job счётчики, `_mi_batch_*`/arrived/scope-маркеры) после завершения/отмены.
   «Нет» = читал класс — ассерты только на финальный статус (COMPLETED/число задач). «Не знаю» = не читал.
3. Ссылка фикстура→тест построена grep'ом `test/files/<имя>` по `src/test/java` (engine + rest); один файл может
   покрываться несколькими классами — в таблице названы главные.
4. `BpmnProcessDefinitionModel.java` — JAXB-бинд: список `@XmlElement` (`:31-86`) есть полный список того,
   что парсится вообще; всё остальное в документе — «тихо игнорируется» (§4).
5. Ограничения фазы 0: не проверялась семантика FEEL-выражений в условиях/фильтрах (только факт парсинга),
   не проверялся runtime-путь AMQP-воркеров (только факт enqueue/handler'а), не проверялись `ProcessInstance*Resource`
   REST-эндпоинты (вне BPMN-деплоя).
