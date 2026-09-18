# Camunda 8 (Zeebe) ↔ ZorroBPM: построчный gap-анализ BPMN/DMN

Дата: 2026-09-04. Read-only исследование. Продолжает и **уточняет** `docs/analysis/camunda8-compatibility.md` (§4, Ось 3) — та работа явно помечала себя как неисчерпывающую; здесь все её открытые вопросы закрыты построчным чтением кода и прямым фетчем действующей документации Camunda 8 / схемы `zeebe-bpmn-moddle`.

> **CTO-верификация (2026-09-04):** произведено фоновым research-агентом, перепроверено лично перед
> публикацией — не принято на слово. Перепроверены: (1) grep по всей `zorrobpm-engine` на
> `taskHeaders|executionListener|priorityDefinition|versionTag|adHoc|taskListener` и `ManualTask` —
> 0 совпадений, полные пробелы подтверждены; (2) `ErrorEscalationThrower.findErrorBoundary` —
> прочитан построчно, подтверждено: `return element` на первом совпадении в цикле, без второго
> прохода на приоритет конкретного кода над catch-all; (3) `CallActivityHandler.handle` — прочитан
> построчно, подтверждено: `processId` берётся как литеральная строка (`.getProcessId()`, без
> `=`-детекции FEEL), `bindingType` нигде не читается, всегда `getMaxProcessDefinitionVersionByKey`
> (latest); (4) `DmnDecisionModel.java` — подтверждено: только `id`/`name`/`decisionTable`, нет
> `literalExpression`/`informationRequirement`; (5) `CancelEndHandler.elementType()` — подтверждено
> существование и регистрация на `CANCEL_END_EVENT` (обосновывает находку «шире Zeebe», §C.4). Эпик
> и первые WO — `governance/workorders/WO-C8-camunda8-bpmn-parity-epic.md`.

## Резюме (самое важное)

Фундамент подтверждён и оказался даже прочнее, чем предполагала первая проверка: диспетчер хендлеров (`HandlerRegistry.java`) реально покрывает **все** 43 значения `BpmnElementType` (прямым хендлером или явным алиасом типа RECEIVE_TASK→MESSAGE_CATCH_EVENT) — заглушек/no-op в enum нет. Error- и escalation-boundary события матчатся **по точному коду**, а не просто по факту наличия боундари (`ErrorEscalationThrower.java`) — это было под вопросом в прошлой версии отчёта, теперь подтверждено чтением кода. Multi-instance (parallel/sequential/completionCondition/outputCollection) и io-mapping реально применяются в рантайме, не только парсятся.

Но при построчной сверке против актуальной схемы `zeebe.json` (camunda/zeebe-bpmn-moddle, зафетчено напрямую) нашёлся набор **конкретных, ранее не задокументированных полных пробелов**: `zeebe:taskHeaders` вообще не парсится (ни один класс `ExtensionElements`/`*Model` его не содержит) — это значит, что кастомные headers, которые задают в Camunda 8 Modeler на service task, у нас молча теряются. Аналогично отсутствуют `zeebe:executionListeners`/`zeebe:taskListeners`, `zeebe:priorityDefinition`, `zeebe:versionTag`, `zeebe:taskSchedule` (dueDate/followUpDate у user task) и весь ad-hoc subprocess (`zeebe:adHoc`, элемент типа `AD_HOC_SUB_PROCESS` в enum отсутствует полностью).

Нашлась и более опасная категория — **частичные пробелы, которые не бросят ошибку при деплое, но исполнятся иначе, чем в Camunda 8**: call activity `processId` и business-rule-task `decisionId` у нас читаются как статическая строка без FEEL-вычисления, хотя Camunda 8 явно поддерживает `processId`/`decisionId` как выражение (`= "shipping-" + tenantId`); `bindingType` (`latest`/`deployment`/`versionTag`) парсится в модель call activity, но реально в `CallActivityHandler.java` никогда не читается — движок всегда резолвит "latest", независимо от того, что написано в XML. Самая тонкая находка: официальная документация Camunda 8 явно требует **приоритет конкретного errorCode/escalationCode над catch-all** боундари в одной области видимости, а `ErrorEscalationThrower.findErrorBoundary/findEscalationBoundary` в ZorroBPM возвращает **первый** совпавший элемент в порядке итерации без такого приоритета — при определённом порядке элементов в XML-файле это даст другое поведение, чем в Camunda 8, не в виде ошибки, а тихо.

DMN: literal-expression decisions и decision requirements graph (`informationRequirement`) подтверждены отсутствующими — `DmnDecisionModel` физически не имеет полей под них. Обратный случай — ZorroBPM **шире** Zeebe там, где Camunda 8 явно не поддерживает исполнение: Transaction subprocess / Cancel event (`CANCEL_END_EVENT`, `CANCEL_BOUNDARY_EVENT` у нас есть и работают, у Zeebe — нет), и стандартный BPMN `loopCardinality` для multi-instance (Zeebe считает количество только через `inputCollection`, у нас поддержаны оба пути).

Список всех находок — в таблицах ниже, каждая строка кода — с `file:line`, каждая находка Camunda 8 — с зафетченным URL.

---

## Часть A — источник истины: что исполняет Zeebe/Camunda 8

Зафетчено напрямую (URL реально открыт WebFetch/WebSearch в этой сессии):

| # | URL | Что оттуда взято |
|---|---|---|
| 1 | https://docs.camunda.io/docs/components/modeler/bpmn/bpmn-coverage/ | Сводная таблица поддерживаемых/неподдерживаемых элементов (см. ограничение ниже) |
| 2 | https://raw.githubusercontent.com/camunda/zeebe-bpmn-moddle/master/resources/zeebe.json | Полная схема `zeebe:`-namespace типов и их атрибутов (актуальная, из `camunda/zeebe-bpmn-moddle`) |
| 3 | https://docs.camunda.io/docs/components/modeler/bpmn/link-events/ | Link events — функционально описаны, реально работают (эквивалент двух none-событий) |
| 4 | https://docs.camunda.io/docs/components/modeler/bpmn/multi-instance/ | MI: parallel/sequential, inputCollection/inputElement, outputCollection/outputElement, completionCondition, `for i in 1..n` вместо loopCardinality |
| 5 | https://docs.camunda.io/docs/components/modeler/bpmn/ad-hoc-subprocesses/ | Ad-hoc subprocess: `zeebe:adHoc activeElementsCollection`, completionCondition, cancelRemainingInstances, engine-native vs job-worker режим, версии 8.7+ |
| 6 | https://docs.camunda.io/docs/components/modeler/bpmn/error-events/ | Матчинг error boundary по `errorCode`, catch-all, приоритет конкретного кода над catch-all, пропагация через call activity |
| 7 | https://docs.camunda.io/docs/components/modeler/bpmn/call-activities/ | bindingType (latest/deployment/versionTag), propagateAllChildVariables/propagateAllParentVariables, `processId` как FEEL-выражение |
| 8 | https://docs.camunda.io/docs/components/modeler/bpmn/timer-events/ | ISO 8601 duration/date/cycle + cron, timer как статика ИЛИ FEEL-выражение |
| 9 | https://docs.camunda.io/docs/components/modeler/bpmn/escalation-events/ | Матчинг по escalationCode, приоритет конкретного кода над catch-all, interrupting/non-interrupting семантика |
| 10 | https://docs.camunda.io/docs/components/modeler/bpmn/user-tasks/ | `zeebe:userTask` (рекомендуемый с 8.6): AssignmentDefinition, `zeebe:taskSchedule` (dueDate/followUpDate), `zeebe:priorityDefinition` (0–100, default 50), `zeebe:taskListeners`, форма через `formId`+bindingType |
| 11 | https://docs.camunda.io/docs/components/modeler/bpmn/business-rule-tasks/ | `decisionId` статика ИЛИ FEEL-выражение, `bindingType`, внутренний DMN-движок Camunda 8, incident при ошибке evaluation |

**Ограничение источника #1**: `bpmn-coverage/` кодирует поддержку цветовой заливкой ("зелёным") в интерактивной таблице; при конвертации в markdown WebFetch эта заливка теряется, поэтому первый фетч дал только текстовый пересказ (риск неточности — см. пример ниже с Link events, которые сводная страница назвала "unsupported", а отдельная страница §3 прямо описывает их как рабочие). **Из-за этого таблица `bpmn-coverage/` использована только как черновой ориентир; каждая конкретная строка ниже перепроверена по отдельной документ-странице или по `zeebe.json`, а не по сводной таблице.**

### A.1 — `zeebe.json` (camunda/zeebe-bpmn-moddle) — полный список типов расширения (источник #2)

| zeebe: тип | Атрибуты (по схеме) |
|---|---|
| `ZeebeServiceTask` | retryCounter |
| `TaskDefinition` | type, retries |
| `TaskHeaders` / `Header` | values → {id, key, value} |
| `IoMapping` / `Input` / `Output` | inputParameters/outputParameters → {source, target} |
| `AssignmentDefinition` | assignee, candidateGroups, candidateUsers |
| `TaskSchedule` | dueDate, followUpDate |
| `PriorityDefinition` / `JobPriorityDefinition` | priority |
| `TaskListeners` / `TaskListener` | listeners → {eventType, retries, type} |
| `ExecutionListeners` / `ExecutionListener` | listeners → {eventType, retries, type, headers} |
| `FormDefinition` | formKey, formId, externalReference |
| `UserTaskForm` | id, body |
| `CalledElement` | processId, **processIdExpression**, propagateAllChildVariables, propagateAllParentVariables, businessId |
| `CalledDecision` | decisionId, resultVariable |
| `Subscription` | correlationKey |
| `LoopCharacteristics` | inputCollection, inputElement, outputCollection, outputElement (**нет loopCardinality**) |
| `Script` | expression, resultVariable |
| `VersionTag` | value |
| `BindingTypeSupported` | bindingType, versionTag |
| `AdHoc` | activeElementsCollection, outputCollection, outputElement |
| `LinkedResource(s)` | resourceId, resourceType, linkName |
| `ConditionalFilter` | variableNames, variableEvents |
| `AgentDefinition` | agentType (AI-агент коннектор — очень новая фича) |

---

## Часть B — реальное покрытие ZorroBPM (по коду)

### B.1 Диспетчер хендлеров — все 43 значения `BpmnElementType` учтены

`HandlerRegistry.java:38-43` резолвит бины `TypedElementHandler` и регистрирует алиасы (`registerAliases()`, строки 48-54):

- Прямой хендлер у каждого из: EVENT_BASED_GATEWAY, EXCLUSIVE_GATEWAY, PARALLEL_GATEWAY, INCLUSIVE_GATEWAY, SERVICE_TASK, SEND_TASK, SCRIPT_TASK, BUSINESS_RULE_TASK, USER_TASK, CALL_ACTIVITY, SUB_PROCESS, START_EVENT, END_EVENT, TERMINATE_END_EVENT, ERROR_END_EVENT, ESCALATION_END_EVENT, ESCALATION_THROW_EVENT, CANCEL_END_EVENT, MESSAGE_CATCH_EVENT, MESSAGE_THROW_EVENT, SIGNAL_CATCH_EVENT, SIGNAL_THROW_EVENT, LINK_THROW_EVENT, TIMER_CATCH_EVENT, CONDITIONAL_CATCH_EVENT, COMPENSATION_THROW_EVENT, INTERMEDIATE_CATCH_EVENT (`WaitStateHandler`), INTERMEDIATE_THROW_EVENT.
- Алиасы (`HandlerRegistry.java:49-53`): MESSAGE_START_EVENT/TIMER_START_EVENT/SIGNAL_START_EVENT/LINK_CATCH_EVENT → START_EVENT; RECEIVE_TASK → MESSAGE_CATCH_EVENT.
- Не в реестре и не нужны там: SEQUENCE_FLOW (не активность, обрабатывается `FlowNavigator.processFlow`), EVENT (базовый абстрактный тип, не эмитится парсером напрямую), EVENT_SUB_PROCESS (входится не через обычный `executor.execute`, а напрямую через `EventTrigger.triggerEventSubprocess`, `EventTrigger.java:224-255` — подтверждено), все `*_BOUNDARY_EVENT` (BOUNDARY_TIMER_EVENT, MESSAGE_BOUNDARY_EVENT, SIGNAL_BOUNDARY_EVENT, ERROR_BOUNDARY_EVENT, ESCALATION_BOUNDARY_EVENT, CONDITIONAL_BOUNDARY_EVENT, COMPENSATION_BOUNDARY_EVENT, CANCEL_BOUNDARY_EVENT) — эти не "входятся" как узел потока, а регистрируются как подписки на хост-активность (`BoundaryScheduler.java`) и срабатывают через `EventTrigger.fireBoundary`/`ErrorEscalationThrower`/`CompensationThrowHandler.runCompensation`/`CancelEndHandler` (`CancelEndHandler.java:84`).

**Вывод B.1**: заглушек/`UnsupportedOperationException` в диспетчере нет — каждое значение enum либо реально исполняется, либо намеренно не является "исполняемым узлом потока" (боундари/подпроцесс-событие), что архитектурно корректно и соответствует BPMN-семантике этих типов.

### B.2 `zeebe:` extension-парсер — подтверждённые ПРОБЕЛЫ против `zeebe.json` (A.1)

`ExtensionElements.java:22-41` — полный список того, что реально парсится из `extensionElements`: `taskDefinition`, `assignmentDefinition`, `formDefinition`, `properties`, `calledElement`, `subscription`, `ioMapping`, `calledDecision`, `script`, `loopCharacteristics`. Сверка построчным grep (`grep -rn "taskHeaders|executionListeners|priorityDefinition|versionTag|adHoc|taskListener" ...` — 0 совпадений во всём `zorrobpm-engine`) подтверждает, что следующих типов из A.1 **нет вообще, ни в одном классе**:

- `zeebe:taskHeaders` — отсутствует полностью (нет класса, нет XML-поля).
- `zeebe:executionListeners` / `zeebe:taskListeners` — отсутствуют полностью.
- `zeebe:priorityDefinition` / job priority — отсутствует полностью.
- `zeebe:versionTag` — отсутствует полностью (сам элемент не парсится нигде).
- `zeebe:taskSchedule` (dueDate/followUpDate у user task) — отсутствует полностью.
- `zeebe:adHoc` / ad-hoc subprocess как таковой — `AD_HOC_SUB_PROCESS` отсутствует в `BpmnElementType.java` целиком.
- `formId` + `bindingType` для форм — `FormDefinitionModel.java:12-16` содержит только `formKey`, `externalReference` (нет `formId`).
- `CalledElement.processIdExpression`, `businessId` — `CalledElementModel.java:12-21` и `CallActivityExtensionModel.java` не содержат этих полей.
- `UserTaskForm` (встроенное тело формы в BPMN-файле) — не моделируется (менее критично — это про рендеринг формы, не исполнение процесса).
- `AgentDefinition` — отсутствует (очень новая фича Camunda ≥8.8, низкий приоритет).

### B.3 Условия на sequence flow — реальный путь вычисления (закрыт открытый вопрос прошлого отчёта)

`FlowNavigator.processFlow` (`FlowNavigator.java:114-127`) вызывает `scriptService.evaluateScript(expression, variables)` для `conditionExpression`. `ScriptServiceImpl.evaluateScript` (`ScriptServiceImpl.java:56-59`) использует бин `scriptEngine`, который в `ScriptEngineConfiguration.java:14-19` — это **`FeelUnaryTestsScriptEngineFactory`** ("Unary-tests engine: evaluates sequence-flow conditions"). Отдельный бин `feelExpressionScriptEngine` (`ScriptEngineConfiguration.java:21-25`, `FeelScriptEngineFactory`) используется для script task/io-mapping/multi-instance через `evaluateExpression`. Оба — из той же библиотеки `org.camunda.feel:feel-engine`, что и Zeebe. **Подтверждено**: условия на flow — реальный FEEL, не заглушка и не другой движок.

### B.4 io-mapping — реально применяется, не только парсится

`ElementSupport.applyIoMappings` (`ElementSupport.java:153-176`) вызывается при входе/выходе активности (грепом подтверждено использование в `MultiInstanceExecutor.java:139`, ходах service/user task); `evaluateMapping` (`ElementSupport.java:186-193`) реально гоняет `scriptService.evaluateExpression` на каждый `source` и пишет `ProcessVariable` через `dbService.setVariables`.

### B.5 Multi-instance — реальный объём (`MultiInstanceExecutor.java`)

- Parallel/sequential: `enter()` (`MultiInstanceExecutor.java:58-83`) — `mi.isSequential()` спавнит либо все N сразу, либо только первую.
- `loopCardinality` (стандартный BPMN) **и** `zeebe:loopCharacteristics inputCollection` — оба пути реализованы (`resolveCardinality`, `MultiInstanceExecutor.java:148-171`); согласно источнику #4 (A, стр. "MI: ... loopCardinality vs inputCollection"), Zeebe **не использует** `loopCardinality`-атрибут вообще (в `zeebe.json` у `LoopCharacteristics` нет поля cardinality, только `inputCollection`) — то есть тут ZorroBPM шире (см. §C.4).
- `completionCondition` — реально вычисляется как FEEL-выражение с инжектированными `completedInstances/totalInstances/numberOfInstances/numberOfCompleteInstances/numberOfActiveInstances/numberOfTerminatedInstances` (`MultiInstanceExecutor.java:257-281`) — совпадает по именам переменных с документацией Camunda 8 (источник #4).
- `outputCollection`/`outputElement` — `aggregateMultiInstanceOutput` (`MultiInstanceExecutor.java:114-124`) реально агрегирует по индексу через JSON-список.
- Комментарии в самой модели (`MultiInstanceExtensionModel.java:20-24`, "requires scoped variables — pending") **устарели** — код, который их сопровождает (`MultiInstanceExecutor.java`), эти поля реально использует; это не блокирующий пробел, а неактуальная документация в коде.

### B.6 Error/Escalation — матчинг по коду подтверждён, но БЕЗ приоритета catch-all (важно, см. §C.3)

`ErrorEscalationThrower.findErrorBoundary` (`ErrorEscalationThrower.java:103-124`) и `findEscalationBoundary` (`ErrorEscalationThrower.java:230-251`): цикл по `bpmn.getElements()`, для первого элемента нужного boundary-типа с совпадающим `attachedToRef`, где `boundaryCode == null || boundaryCode.equals(errorCode)` — **возвращает первый попавшийся**, будь то catch-all или конкретный код, в порядке итерации списка элементов (порядок парсинга XML). Официальная документация Camunda 8 (источник #6/#9) явно требует, чтобы **конкретный код имел приоритет над catch-all** в одной области видимости, независимо от порядка объявления в XML. ZorroBPM такого приоритета не реализует — см. §C.3.

Пропагация error/escalation через call activity вверх по иерархии инстансов — подтверждена (`ErrorEscalationThrower.java:73-96` для error, `184-213` для escalation), включая рекурсивный подъём, если родитель не ловит.

### B.7 Compensation — throw+boundary+association, ad-hoc subprocess не поддержан (совпадает с изначальным гейтом C8, у которого ad-hoc compensation вообще другая фича)

`CompensationThrowHandler.runCompensation` (`CompensationThrowHandler.java:70-89`): находит compensation boundary по `attachedToRef`, запускает `compensationHandlerId` синхронно в обратном порядке завершения (`sort by createdAt desc`). Реализовано через boundary+association модель, как того требует BPMN/Zeebe.

### B.8 DMN — literal expression и DRG подтверждены отсутствующими

`DmnDecisionModel.java:14-21` — поля только `id`, `name`, `decisionTable`; нет `literalExpression`, нет `informationRequirement`. `DmnServiceImpl.evaluate` (`DmnServiceImpl.java:88-90`) явно кидает `EngineException("...has no decision table")`, если `table == null` — то есть decision без таблицы (literal expression) гарантированно упадёт, а не молча даст null. Decision-to-decision зависимости (DRG) нигде не читаются — `dmnService.evaluate` принимает один `decisionId` и не разрешает зависимые decisions.

Hit policies — полный набор подтверждён: UNIQUE/FIRST/ANY/COLLECT(+SUM/MIN/MAX/COUNT)/RULE ORDER/OUTPUT ORDER/PRIORITY (`DmnServiceImpl.java:118-130`).

### B.9 Call activity — bindingType распознан парсером, но НЕ применяется в рантайме (реальное расхождение)

`CallActivityExtensionModel.java:9-14` и `CalledElementModel.java:12-21` парсят `bindingType`, `processId`, `propagateAllParentVariables`, `propagateAllChildVariables`. Но `CallActivityHandler.handle` (`CallActivityHandler.java:57-67`) читает **только** `processId` (как buквальную строку, без FEEL — не поддерживает `processIdExpression`/`= "..."`, вопреки источнику #7) и всегда резолвит **последнюю версию** через `dbService.getMaxProcessDefinitionVersionByKey(key)` (`CallActivityHandler.java:63`) — `bindingType` нигде не читается для выбора между `latest`/`deployment`/`versionTag`. Модель, задеплоенная с `bindingType="deployment"` (типично для транзакционной консистентности вызываемого процесса), в ZorroBPM всегда получит последнюю опубликованную версию — тихое расхождение поведения.

### B.10 Business rule task — то же расхождение, что и call activity

`SyncTaskHandler.BusinessRuleTask.handle` (`SyncTaskHandler.java:111-112`): `dmnService.evaluate(ext.getDecisionId(), variables)` — `decisionId` передаётся как есть, без проверки `=`-префикса и без вызова FEEL, хотя Camunda 8 явно поддерживает `decisionId` как выражение (источник #11). `bindingType` для DMN-решений в схеме (`CalledDecisionModel.java:13-18`) вообще не парсится (только `decisionId`+`resultVariable`), и `DmnServiceImpl.evaluate` всегда берёт последнюю версию decision (`findFirstByDecisionIdOrderByVersionDesc`, `DmnServiceImpl.java:80-81`) — нет способа зафиксировать версию решения к версии процесса.

### B.11 Manual Task / undefined Task — нет парсинга вообще

`grep -rln "ManualTask" --include=*.java` по всему `zorrobpm-engine` → 0 совпадений. Camunda 8 явно перечисляет Manual Task и Undefined Task как поддерживаемые для выполнения (источник #1, "Supported Elements"). У ZorroBPM нет ни модели, ни хендлера для `<bpmn:manualTask>` — при парсинге такой элемент, скорее всего, просто не попадёт ни в один из циклов `process.get*Tasks()` в `BpmnParseServiceImpl.java` (там перечислены явно service/send/receive/script/businessRule/user — manualTask нет в этом списке) и будет молча проигнорирован (не полетит как ошибка парсинга, но и не будет исполнен как узел потока — вероятно сломает граф потока, если на него ведёт sequence flow, т.к. `bpmn.getElement(targetRef)` не найдёт элемент — не проверялось на реальном XML в этом заходе, помечаю как "не удалось экспериментально проверить").

---

## Часть C — итоговая gap-таблица

### C.1 Полные пробелы (Camunda 8 умеет, у ZorroBPM нет вообще)

| Конструкция | Camunda 8 | ZorroBPM | Вердикт | Сложность закрытия |
|---|---|---|---|---|
| `zeebe:taskHeaders` (кастомные headers на service/user task) | Да — источник #2 (`TaskHeaders`/`Header`) | Нет — `ExtensionElements.java:22-41` не содержит поля | ОТСУТСТВУЕТ | Тривиально — новая XML-модель + прокидка в `JobDetailModel` |
| `zeebe:executionListeners` / `zeebe:taskListeners` | Да, источник #2 и #10 | Нет — grep 0 совпадений | ОТСУТСТВУЕТ | Средне — нужен новый механизм вызова листенеров в жизненном цикле активности |
| `zeebe:priorityDefinition` / job/task priority | Да (0–100, default 50), источник #2/#10 | Нет | ОТСУТСТВУЕТ | Средне — влияет на очередь/диспетчеризацию задач |
| `zeebe:versionTag` (на процессе и в bindingType) | Да, источник #2/#7 | Нет — не парсится нигде | ОТСУТСТВУЕТ | Средне — требует версионирования по тегу в БД определений |
| `zeebe:taskSchedule` (dueDate/followUpDate у user task) | Да, источник #2/#10 | Нет | ОТСУТСТВУЕТ | Тривиально-средне — новое поле + FEEL-вычисление аналогично timer |
| Ad-hoc subprocess (`zeebe:adHoc`, тип `AD_HOC_SUB_PROCESS`) | Да, с 8.7 (источник #5) | Нет — тип отсутствует в `BpmnElementType.java` целиком | ОТСУТСТВУЕТ | Сложно — новая исполнительная модель (динамическая активация элементов вне обычного sequence-flow) |
| `formId` + bindingType форм | Да, источник #2/#10 | Нет — `FormDefinitionModel.java` только `formKey`/`externalReference` | ОТСУТСТВУЕТ | Тривиально (данные), но требует Form-сервиса с версионированием |
| DMN literal expression decision | Да | Нет — `DmnDecisionModel.java` не содержит поля, `evaluate()` кидает исключение при `decisionTable == null` (`DmnServiceImpl.java:88-90`) | ОТСУТСТВУЕТ | Тривиально — decision без таблицы = прямой FEEL eval выражения |
| DMN decision requirements graph (`informationRequirement`) | Да | Нет — не моделируется, `evaluate()` принимает один decisionId без разрешения зависимостей | ОТСУТСТВУЕТ | Средне — нужен граф зависимостей + порядок вычисления |
| Manual Task / undefined Task | Да (§B.1, "Supported Elements") | Нет — не парсится (`BpmnParseServiceImpl.java`, нет `getManualTasks()`) | ОТСУТСТВУЕТ | Тривиально — pass-through хендлер, как у START_EVENT |
| `CalledElement.processIdExpression` / `businessId` | Да, источник #2/#7 | Нет — `CalledElementModel.java:12-21` не содержит полей | ОТСУТСТВУЕТ | Тривиально (данные) |
| `zeebe:calledDecision` bindingType | Подразумевается той же `BindingTypeSupported` схемой, источник #2/#11 | Нет — `CalledDecisionModel.java:13-18` только decisionId/resultVariable | ОТСУТСТВУЕТ | Средне — версионирование DMN-решений |
| `UserTaskForm` (встроенное тело формы в BPMN) | Да, источник #2 | Нет | ОТСУТСТВУЕТ (низкий приоритет — про рендеринг, не движок) | Тривиально |
| `AgentDefinition` (AI-агент коннектор) | Да, очень новое (≥8.8) | Нет | ОТСУТСТВУЕТ (низкий приоритет, нишевое) | Не оценивалось |
| Complex Gateway | **Тоже нет у Zeebe** (источник #1) | Нет | Не применимо — обе стороны не поддерживают | — |

### C.2 Частичные пробелы (форма есть, семантика неполная)

| Конструкция | Camunda 8 | ZorroBPM | Деталь |
|---|---|---|---|
| Call activity `processId` | Статика ИЛИ FEEL-выражение (источник #7) | Только статика — `CallActivityHandler.java:57-61` использует значение как есть, без `=`-детекции | Модель с `processId="= \"proc-\" + tenantId"` задеплоится, но резолвится в буквальную (неверную) строку и упадёт в инцидент "no deployed definition" |
| Call activity `bindingType` | latest/deployment/versionTag реально выбирают версию (источник #7) | Поле парсится (`CallActivityExtensionModel.java:10`), но **никогда не читается** в `CallActivityHandler.java` — всегда `getMaxProcessDefinitionVersionByKey` (latest) | Тихое расхождение поведения — не ошибка, другой результат (см. §C.3) |
| Business rule task `decisionId` | Статика ИЛИ FEEL-выражение (источник #11) | Только статика — `SyncTaskHandler.java:111-112` | Аналогично call activity — expression-decisionId не вычисляется |
| Error/Escalation boundary: catch-all vs конкретный код | Конкретный код приоритетнее catch-all в одной области (источник #6/#9) | Приоритета нет — первый совпавший элемент в порядке итерации (`ErrorEscalationThrower.java:103-124`, `230-251`) | См. §C.3 — потенциально другое поведение без ошибки |
| Multi-instance на job-worker intermediate throw events | Не поддержан у Zeebe тоже (источник #4, "Key Limitation") | Не проверялось отдельно | Обе стороны, вероятно, совпадают — не приоритет |

### C.3 Реальные расхождения в поведении (задеплоится без ошибки, исполнится иначе)

1. **Error/Escalation catch-all-приоритет.** Camunda 8: если у одной активности одновременно висит error boundary с конкретным `errorCode="INSUFFICIENT_FUNDS"` и error boundary без кода (catch-all), и брошена именно эта ошибка — Camunda 8 гарантированно поймает её специфичным боундари. ZorroBPM поймает **тот, что раньше встретился при итерации `bpmn.getElements()`** (порядок парсинга XML, не гарантированно совпадает с порядком, в котором Modeler их сохраняет) — `ErrorEscalationThrower.java:103-124` (error), `230-251` (escalation). Модель, экспортированная из Camunda 8 Modeler с обоими типами боундари на одной задаче, может в ZorroBPM пойти по catch-all-ветке вместо специфичной — молча, без инцидента.
2. **Call activity `bindingType="deployment"`/`"versionTag"`.** Camунда 8 гарантирует, что вызываемый процесс останется той же версией, что была на момент деплоя вызывающего (при `deployment`) или зафиксирован тегом (при `versionTag`). ZorroBPM всегда берёт **latest** (`CallActivityHandler.java:63`) — если после деплоя вызывающего процесса кто-то задеплоил новую версию вызываемого, поведение разойдётся: Camunda 8 продолжит использовать старую (ожидаемую автором модели) версию, ZorroBPM подхватит новую.
3. **Business rule task с версионируемыми decision.** Аналогично п.2 — `DmnServiceImpl.evaluate` всегда resolves latest version decision, без привязки к bindingType/versionTag.

### C.4 Что шире, чем в Zeebe (у нас есть, у них нет/убрано)

| Конструкция | Camunda 8 | ZorroBPM | Комментарий |
|---|---|---|---|
| `CANCEL_END_EVENT` / `CANCEL_BOUNDARY_EVENT` (BPMN Transaction subprocess элементы) | **Не поддержан** — источник #1 явно перечисляет "Transaction subprocess" и "Cancel event" в Unsupported | Поддержан и работает — `CancelEndHandler.java`, `handler/CancelEndHandler.java:36,84` | Модель с BPMN-транзакциями из Camunda 8 Modeler не задеплоится в саму Camunda 8 (Modeler не даст экспортировать такую диаграмму для Zeebe-исполнения), так что практической ценности для миграции немного, но для полноты зафиксировано |
| Стандартный BPMN `loopCardinality` для multi-instance | **Не используется** — `zeebe.json` `LoopCharacteristics` не имеет поля cardinality, Zeebe считает количество только через `inputCollection` (или паттерн `for i in 1..n`) — источник #4 | Поддержан как fallback наравне с `inputCollection` — `MultiInstanceExecutor.java:148-171` | Не проблема — просто дополнительная гибкость, не влияющая на совместимость (модели из Camunda 8 Modeler всегда используют inputCollection) |

---

## Ограничения этого захода (честно не проверено)

- **Complex Gateway, Pools/Lanes, Data Objects/Data Stores** — не проверялись построчно в коде ZorroBPM (по документации Camunda 8 они либо не исполняются вообще (Complex Gateway, Data Objects — "supported for modeling only"), либо не влияют на исполнение (pools/lanes — организационные элементы) — низкий приоритет, но не подтверждено экспериментально на реальном XML-файле).
- **`bpmn-coverage/` сводная таблица** — её цветовая кодировка не читается через markdown-конвертацию WebFetch; каждая конкретная строка в этом отчёте перепроверена по отдельной странице документации или по `zeebe.json`, но полный построчный обход всех ~80 строк той таблицы не выполнялся (не нужно — есть более надёжные точечные источники).
- **`zeebe:script` (script task) — язык.** Подтверждено, что и Zeebe, и ZorroBPM используют FEEL-выражение (`ZeebeScriptModel.java`, комментарий "inline FEEL expression"), но не проверялось, поддерживает ли Camunda 8 что-то ещё (JUEL/Groovy) для script task — по документации современный `zeebe:script` — только FEEL, так что расхождения не ожидается.
- **Manual Task поведение при реальном парсинге** — заявлено по коду (нет в списке циклов `BpmnParseServiceImpl`), но не проверено экспериментально прогоном реального `.bpmn`-файла с `<bpmn:manualTask>` через парсер — не удалось проверить в рамках read-only анализа без тестового запуска.
- **Точная семантика unary-tests engine для conditionExpression** — подтверждено, что используется `FeelUnaryTestsScriptEngineFactory` (та же feel-scala линия), но побитовое сравнение с тем, как именно Zeebe internally вызывает FEEL для `conditionExpression` (Zeebe тоже использует unary-tests семантику для boolean conditionExpression) не перепроверялось против исходников Zeebe engine — предположение основано на официальной FEEL-спецификации, которой оба следуют.
- **gRPC/REST/Job-worker протокол (Оси 1 и 2)** — не пересматривались в этом заходе, см. существующий `docs/analysis/camunda8-compatibility.md` §2–3 (не изменился, вне scope этой задачи).

---

## Addendum (2026-09-04, CTO лично) — реальный Zeebe 8.6, не только документация

Всё выше — сверка кода против документации/схемы, не против живого движка. Поднял локально
реальный Camunda 8.6 (Zeebe broker + Elasticsearch exporter + Operate/Tasklist query-слой,
`camunda/zeebe:8.6.0`, non-production use — покрывается бесплатной Camunda Self-Managed
Non-Production License, см. `docs.camunda.io/docs/reference/licenses/`) и прогнал через него ТЕ ЖЕ
fixture-файлы, что уже лежат в `zorrobpm-engine/src/test/files/` (WO-C8-1).

**Error-priority (`test-c8-error-priority.bpmn`) — подтверждено эмпирически, не только по докам.**
Тот же XML-файл (catch-all боундари объявлен ПЕРВЫМ, specific-боундари — ВТОРЫМ), задеплоен и
запущен в реальном Zeebe 8.6: полная трасса событий (Elasticsearch-экспорт стрима, `partitionId=1`)
показывает, что боундари `specific` (errorCode-специфичный) активируется и завершается,
`endSpecific` достигается, а `catchAll`/`endCatchAll` **не активируются вообще** — ни разу за весь
жизненный цикл инстанса. Т.е. Camunda 8 реально игнорирует порядок объявления в XML и всегда берёт
специфичный код. Это **ровно противоположно** тому, что доказал `errorBoundary_specificVsCatchAll_
firstMatchInIterationWins` в WO-C8-1 для ZorroBPM (у нас с тем же XML побеждает `catchAll`, т.к.
он объявлен первым). Расхождение подтверждено на обеих сторонах реальным прогоном, не
предположением по коду ни с одной из сторон. Усиливает приоритет WO-C8-4 (error/escalation
приоритет) — это не гипотетический, а дважды эмпирически подтверждённый баг.

**Manual Task** — задеплоено успешно в реальный Zeebe 8.6 (`<bpmn:manualTask>` в потоке между
двумя обычными задачами, HTTP 200, `processDefinitionKey` выдан) — подтверждает §C.1 находку
"поддерживается" эмпирически, не только по перечню в документации Camunda. Полную трассу
исполнения (завершился ли инстанс) не снял — упёрся в auth-стену Camunda 8.6's unified security
layer (`CAMUNDA_SECURITY_AUTHENTICATION_UNPROTECTED_API=true` не убрал 403 на `/v2/deployments`/
`/v2/element-instances/search` для последующих попыток — вероятно нужна полноценная RBAC/Identity
настройка, не тривиальный флаг; не стал углубляться дальше ради одной этой конструкции).

**Escalation-priority — НЕ прогнано на реальном Zeebe** (тот же auth-барьер настиг раньше, чем
успел продеплоить `test-c8-escalation-parent.bpmn`). Официальная документация Camunda 8
(источник #9 в §A) утверждает симметричный механизм с error-приоритетом — экстраполирую по
симметрии и документации, но это НЕ независимо подтверждено эмпирически так же, как error. Стоит
попробовать снова при следующей возможности (например когда WO-C8-4 будет в работе) — не
дублировать эту попытку бездумно, сначала разобраться с auth (либо полноценный
`docker-compose` из `camunda/camunda-platform` с уже готовой конфигурацией, либо gRPC-путь через
`zbctl`/клиент вместо REST v2, который может не иметь той же auth-стены).

Сырая ES-трасса (JSON, 31 событие) сохранена вне репозитория — не коммитил, доказательство здесь и
в отчёте достаточно; при необходимости можно легко переснять (сетап поднимается за ~2 минуты, все
команды — `docker run camunda/zeebe:8.6.0` + `docker.elastic.co/elasticsearch/elasticsearch:8.13.4`
+ ES exporter env vars).

---

## Файлы, прочитанные построчно (для воспроизводимости)

`zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/bpmn/model/BpmnElementType.java`; `.../bpmn/xml/ExtensionElements.java`; `.../bpmn/xml/extension/{AssignmentDefinitionModel,CalledDecisionModel,CalledElementModel,FormDefinitionModel,IoMappingModel,SubscriptionModel,TaskDefinitionModel,UserTaskExtensionModel,ZeebeLoopCharacteristicsModel,ZeebeScriptModel}.java`; `.../bpmn/xml/BpmnMultiInstanceModel.java`, `BpmnExclusiveGatewayModel.java`; `.../bpmn/model/{CallActivityExtensionModel,ExclusiveGatewayExtensionModel,MultiInstanceExtensionModel,BusinessRuleExtensionModel,BpmnConditionExpressionModel}.java`; `.../handler/{HandlerRegistry,ElementSupport,BoundaryScheduler,ErrorEscalationThrower,EventTrigger,CompensationThrowHandler,MultiInstanceExecutor,FlowNavigator,CallActivityHandler,StartThrowEventHandler,SyncTaskHandler,IncidentService}.java`; `.../service/impl/{BpmnParseServiceImpl(частично, 1-220 строк),DmnServiceImpl,ScriptServiceImpl}.java`; `.../configuration/ScriptEngineConfiguration.java`; `.../dmn/xml/DmnDecisionModel.java`.
