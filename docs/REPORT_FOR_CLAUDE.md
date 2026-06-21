# Executive Summary

ZorroBPM CE — лёгкий BPMN 2.0 движок на Spring Boot 4.0.5 / Java 21 / PostgreSQL. Многомодульный Maven-проект (v0.7.17-SNAPSHOT), реализующий парсинг BPMN XML через JAXB, исполнение графа токенами, интеграцию с RabbitMQ для внешних воркеров, DMN-движок на FEEL.

Движок находится на ранней стадии (Community Edition). Happy-path покрыт интеграционными тестами (51 тестовый класс, ~191 тестовых методов). Нет аутентификации/авторизации, нет поддержки кластера (таймеры без leader-election), есть критические NPE в Call Activity и several concurrency gaps. Код написан аккуратно, но содержит системные проблемы транзакционности и several edge cases без guard-проверок.

**Структура модулей:**
- `zorrobpm-contract` — контракты REST API, DTO, исключения
- `zorrobpm-engine` — ядро: парсинг BPMN, исполнение, persistence (JPA), таймеры, DMN
- `zorrobpm-rest` — REST-контроллеры (3 контроллера)
- `zorrobpm-rabbitmq` — RabbitMQ: сервис-задачи → воркеры, завершения ← воркеры
- `zorrobpm-event` / `zorrobpm-exchange` — доменные события и модели обмена
- `zorrobpm-client` — Java REST-клиенты
- `zorrobpm-job-handler-spring-boot-starter` — SDK для внешних воркеров
- `zorrobpm-ce` — Bootstrap-приложение
- `zorrobpm-test` — тестовые хелперы

---

# Critical Bugs

## BUG-1: NPE в processCallActivity при отсутствии processId

- **Класс**: `ActivityServiceImpl`
- **Метод**: `processCallActivity`
- **Строка**: 649
- **Код**:
  ```java
  String key = bpmnElement.getExtensions().getCallActivityExtension().getProcessId();
  ```
- **Сценарий**: BPMN XML содержит `<bpmn:callActivity>` без `<zeebe:calledElement>`, или `<zeebe:calledElement>` без атрибута `processId`. При парсинге (`BpmnParseServiceImpl:928-936`) `callActivityExtension` создаётся, но `processId` остаётся null.
- **Последствия**: `NullPointerException` на строке 649 → ловится `catch(Exception)` в `execute()` на строке 628 → создаёт инцидент с непонятным сообщением "NullPointerException". Процесс зависает в состоянии инцидента. Оператор не видит причину.

## BUG-2: NPE в processCallActivity при отсутствии extensions

- **Класс**: `ActivityServiceImpl`
- **Метод**: `processCallActivity`
- **Строка**: 649
- **Код**:
  ```java
  String key = bpmnElement.getExtensions().getCallActivityExtension().getProcessId();
  ```
- **Сценарий**: `BpmnParseServiceImpl:928` создаёт extensions только если `callActivity.getExtensionElements() != null`. Если extensions отсутствуют — `bpmnElement.getExtensions()` == null → NPE.
- **Последствия**: аналогично BUG-1, инцидент с непонятным сообщением.

## BUG-3: NPE при отсутствии целевого процесса

- **Класс**: `ActivityServiceImpl`
- **Метод**: `processCallActivity`
- **Строка**: 651
- **Код**:
  ```java
  ProcessDefinition pd = dbService.getProcessDefinition(key, version);
  ```
- **Сценарий**: `key` (processId) ссылается на процесс, который не был задеплоен. `getProcessDefinition` (`DBServiceImpl:313`) вызывает `orElseThrow()` → `NoSuchElementException`, **не NPE**. Но если `key == null` (BUG-1), то `getMaxProcessDefinitionVersionByKey(null)` выполнит SQL-запрос с null ключом, что может вернуть 0 → `findByKeyAndVersion(null, 0)` → `NoSuchElementException`.
- **Последствия**: инцидент с `NoSuchElementException` вместо информативного сообщения.

## BUG-4: NPE в finishBranch при возврате из call activity

- **Класс**: `ActivityServiceImpl`
- **Метод**: `finishBranch`
- **Строка**: 1805
- **Код**:
  ```java
  BpmnElementModel parentBpmnElement = parentBpmn.getElement(parentActivity.getBpmnElementId());
  ```
- **Сценарий**: `getElement()` возвращает null, если `parentActivity.getBpmnElementId()` не найден в модели parent process. Далее `proceedToOutgoing(...)` на строке 1807 → `element.getOutgoing()` → NPE.
- **Последствия**: дочерний процесс завершился, переменные скопированы, но parent процесс застрял в состоянии "call activity активна". Инцидент создаётся, но parent процесс не продолжает выполнение корректно.

## BUG-5: NPE в proceedToOutgoing при отсутствии sequence flow

- **Класс**: `ActivityServiceImpl`
- **Метод**: `proceedToOutgoing`
- **Строки**: 156-157
- **Код**:
  ```java
  BpmnFlowModel flow = bpmn.getFlow(outgoing);
  BpmnElementModel target = bpmn.getElement(flow.getTargetRef());
  ```
- **Сценарий**: если `bpmn.getFlow(outgoing)` возвращает null (sequence flow не найден в модели), `flow.getTargetRef()` → NPE.
- **Последствия**: инцидент на элементе. Возможен при некорректном BPMN XML.

## BUG-6: NPE в execute при null element

- **Класс**: `ActivityServiceImpl`
- **Метод**: `execute` (private)
- **Строка**: 609
- **Код**:
  ```java
  BpmnElementType type = element.getType();
  ```
- **Сценарий**: `execute(processInstanceId, tokenId, bpmn, element)` вызывается с `element == null`. Это возможно из `proceedToOutgoing:158` если `target == null`, или из `processCallActivity` через chain.
- **Последствия**: инцидент с NPE.

## BUG-7: NPE в startProcessInstanceAt при удалённом parent activity

- **Класс**: `ActivityServiceImpl`
- **Метод**: `startProcessInstanceAt`
- **Строки**: 1662-1664
- **Код**:
  ```java
  Activity activity = dbService.getActivity(parentActivityId);
  Token token = dbService.getToken(activity.getToken());
  parentTokenId = token.getId();
  ```
- **Сценарий**: parent activity была отменена (terminate end в параллельной ветке) в момент запуска дочернего процесса. `getActivity` выбросит `NoSuchElementException` (не NPE), но `activity.getToken()` может вернуть null если activity была частично инициализирована.
- **Последствия**: дочерний процесс не запускается, parent процесс застрял.

## BUG-8: NPE в fireBoundary при отсутствии boundary элемента

- **Класс**: `ActivityServiceImpl`
- **Метод**: `fireBoundary`
- **Строки**: 1445-1447
- **Код**:
  ```java
  BpmnElementModel boundary = bpmn.getElement(boundaryElementId);
  boolean interrupting = Optional.ofNullable(boundary.getExtensions())
  ```
- **Сценарий**: `boundaryElementId` ссылается на boundary event, который не найден в модели → `boundary == null` → `boundary.getExtensions()` → NPE.
- **Последствия**: таймер/сообщение сработало, но boundary не может быть обработан. Токен застрял.

## BUG-9: Баг-9: getProcessDefinitionModelById может вернуть null

- **Класс**: `BpmnServiceImpl`
- **Метод**: `getProcessDefinitionModelById`
- **Строки**: 32-37
- **Код**:
  ```java
  if (!models.containsKey(id)) {
      String bpmn = fileService.getFileBytes(id);
      BpmnProcessDefinitionModel model = bpmnParseService.parse(bpmn);
      addProcessDefinition(id, model);
  }
  return models.get(id);
  ```
- **Сценарий**: `ConcurrentHashMap.containsKey()` и `get()` не атомарны. Два потока одновременно проходят `containsKey == false`. Оба загружают файл. Если `fileService.getFileBytes()` возвращает null (файл удалён), `bpmnParseService.parse(null)` → NPE внутри JAXB. Если `parse` выбрасывает исключение — `models.get(id)` вернёт null → вызывающий код получит NPE.
- **Последствия**: все операции с процессом падают с NPE.

---

# Camunda 8 Compatibility

## Что поддерживается полностью (✅)

| Класс | Методы парсинга | Файлы |
|---|---|---|
| Start/End/Terminate события | `BpmnParseServiceImpl:90-132,134-140,757-774,776-800` | `BpmnStartEventModel`, `BpmnEndEventModel` |
| Message start/catch/throw/boundary | `BpmnParseServiceImpl:95-103,835-873,282-307` | `MessageEventExtensionModel` |
| Timer start/catch/boundary | `BpmnParseServiceImpl:104-117,849-861,394-404` | `TimerEventExtensionModel` |
| Signal start/catch/throw/boundary | `BpmnParseServiceImpl:239-241,839,892-893,412-414` | `EventDefinitionType.SIGNAL` |
| Error end/boundary | `BpmnParseServiceImpl:137,763,405-408` | `EventDefinitionType.ERROR` |
| Escalation throw/end/boundary | `BpmnParseServiceImpl:356-359,765-766,894,415-418` | `EventDefinitionType.ESCALATION` |
| Link catch/throw | `BpmnParseServiceImpl:841,896-897,363-364` | `EventDefinitionType.LINK` |
| Exclusive/Parallel/Inclusive/Event-based gateway | `BpmnParseServiceImpl:184-228` | `ExclusiveGatewayExtensionModel` |
| Service Task (`zeebe:taskDefinition`) | `BpmnParseServiceImpl:574-588` | `ServiceTaskExtensionModel` |
| User Task (`zeebe:assignmentDefinition`) | `BpmnParseServiceImpl:711-755` | `UserTaskExtensionModel` |
| Receive Task (`messageRef`) | `BpmnParseServiceImpl:694-709` | `MessageEventExtensionModel` |
| Script Task (`zeebe:script` + inline) | `BpmnParseServiceImpl:622-646` | `ScriptTaskExtensionModel` |
| Business Rule Task (`zeebe:calledDecision`) | `BpmnParseServiceImpl:657-676` | `BusinessRuleExtensionModel` |
| Call Activity (`zeebe:calledElement`) | `BpmnParseServiceImpl:921-939` | `CallActivityExtensionModel` |
| Embedded Subprocess | `BpmnParseServiceImpl:435-539` | `SubProcessExtensionModel` |
| Event Subprocess (message/signal/error/timer) | `BpmnParseServiceImpl:439-484` | `SubProcessExtensionModel` |
| IO Mappings (`zeebe:ioMapping`) | `BpmnParseServiceImpl:590-620` | `IoMappingExtensionModel` |
| Correlation Key (`zeebe:subscription`) | `BpmnParseServiceImpl:53-60` | `MessageEventExtensionModel.correlationKeyExpression` |

## Что реализовано частично (⚠️)

| Класс | Проблема | Строки |
|---|---|---|
| `ActivityServiceImpl` | Multi-instance: per-instance `inputElement`/`outputCollection` парсятся из `zeebe:loopCharacteristics`, но scoped-изоляция переменных (loopCounter) не реализована | `:1151-1166` |
| `ActivityServiceImpl` | Compensation: compensate-all + targeted по `activityRef` ✅; компенсация в scope встроенного подпроцесса — отдельный заход | `:211-246` |
| `ActivityServiceImpl` | `propagateAllChildVariables` парсится, но не используется при call activity return | `:1800-1801` |

## Что отсутствует в Camunda 8 (стандарт BPMN, но C8 не исполняет)

| Элемент | Статус | Рекомендация |
|---|---|---|
| Conditional start/catch/boundary | ❌ Не в C8 | Заменять на шлюз с FEEL-условием |
| Transaction subprocess | ❌ Не в C8 | Заменять на embedded subprocess + компенсация |
| Cancel end/boundary | ❌ Не в C8 | Заменять на error-события |

## Рекомендации по совместимости

1. **`timeCycle` таймеры** — не парсятся (`BpmnParseServiceImpl:109-116` парсит только `timeDate`/`timeDuration`). C8 поддерживает. Добавить парсинг.
2. **DMN versioning** — нет (перезапись при повторном деплое, `DmnServiceImpl:52`).
3. **Script task format** — поддерживается только `feel`/inline. C8 поддерживает `python`, `javascript`.
4. **Per-instance multi-instance variables** — `inputElement`/`outputCollection` парсятся из `zeebe:loopCharacteristics`, но scoped-изоляция переменных (loopCounter) не реализована. Требует Scoped Variables.

---

# BPMN Engine Risks

## Call Activity

| Риск | Файл:Строка | Описание |
|---|---|---|
| NPE на processId | `ActivityServiceImpl:649` | Без null-проверки extensions/callActivityExtension/processId |
| NPE на parent activity | `ActivityServiceImpl:1662-1664` | parent activity может быть удалён к моменту запуска child |
| NPE на parentBpmnElement | `ActivityServiceImpl:1805` | getElement() возвращает null если элемент не найден в parent BPMN |
| Missing propagateAllChildVariables | `ActivityServiceImpl:1800-1801` | Всегда копирует переменные child→parent, игнорируя флаг |
| Parent token deadlock | `ActivityServiceImpl:1790-1791` | lockProcessInstance на parent с child→parent ordering, безопасно при единственном child |

## Multi Instance

| Риск | Файл:Строка | Описание |
|---|---|---|
| Per-instance variables | `ActivityServiceImpl:1151-1166` | `inputElement`/`outputCollection` парсятся из `zeebe:loopCharacteristics`, но scoped-изоляция переменных (loopCounter) не реализована |
| NPE при completionCondition | `ActivityServiceImpl:1249-1258` | `completionConditionMet()` вызывается даже если `mi.getCompletionCondition() == null` → false (защищено) |
| Sequential spawn на токене | `ActivityServiceImpl:1237-1241` | Следующий sequential instance создаётся на том же токене без scope-изоляции |
| Reflection fallback | `ActivityServiceImpl:1207-1213` | `collectionSize()` использует рефлексию для Scala collections — хрупко |
| Zeebe inputCollection | `ActivityServiceImpl:1179-1181` | ✅ Поддержано: количество инстансов берётся из `zeebe:loopCharacteristics inputCollection` (размер коллекции) |

## Event Subprocess

| Риск | Файл:Строка | Описание |
|---|---|---|
| Error trigger | `ActivityServiceImpl:1899-1907,1938-1951` | ✅ Реализовано: `throwError` ищет error-triggered event subprocess перед пропагацией в parent |
| Interrupting cancel | `ActivityServiceImpl:1557-1564` | При interrupting: `cancelActiveActivities` отменяет все活动, создаёт token без parentId |
| Non-interrupting scope | `ActivityServiceImpl:1569-1574` | Non-interrupting: создаёт branch token + scope token, но не удаляется при завершении main flow |
| Signal trigger | `ActivityServiceImpl:1598-1607` | ✅ Реализовано: `broadcastSignal` обрабатывает signal-started event subprocess (interrupting) |
| Timer trigger | `ActivityServiceImpl:1697-1701` | ✅ Реализовано: `subscribeEventSubprocesses` создаёт timer jobs для timer-triggered event subprocess |
| Race condition | `ActivityServiceImpl:1541` | `lockProcessInstance` вызывается перед проверкой `completedAt` — корректно |

## Boundary Events

| Риск | Файл:Строка | Описание |
|---|---|---|
| NPE при boundary == null | `ActivityServiceImpl:1445-1447` | Если boundary не найден в модели → NPE |
| Interrupting cancel scope | `ActivityServiceImpl:1452-1455` | При interrupting: `cancelActivity` + `proceedToOutgoing` — но token не сменяется |
| Non-interrupting branch leak | `ActivityServiceImpl:1459-1461` | Non-interrupting создаёт branch token, который не отслеживается parent token |
| Conditional boundary re-evaluation | `ActivityServiceImpl:520-527` | `findConditionalBoundaries()` ищет на каждый вызов `triggerConditionalEvents` — O(n) |

## Compensation

| Риск | Файл:Строка | Описание |
|---|---|---|
| compensate-all scope | `ActivityServiceImpl:216` | `getCompletedActivities(processInstanceId)` — все activity инстанса, не только текущий scope |
| Targeted compensation | `ActivityServiceImpl:211-246` | ✅ Реализовано: `activityRef` фильтрует конкретную активность при компенсации |
| Compensation handler not found | `ActivityServiceImpl:239` | Если handlerId == null или элемент не найден — пропуск (не ошибка) |
| Reverse order only | `ActivityServiceImpl:228-229` | Сортировка по `createdAt` — корректно для sequential, но нет guarantee для parallel |

## Transactions

| Риск | Файл:Строка | Описание |
|---|---|---|
| Nested transactions | `ActivityServiceImpl:253-254` | Вложенные транзакции не поддерживаются — comment в коде |
| Cancel end outside scope | `ActivityServiceImpl:261-264` | Cancel end вне transaction scope → plain end (fallback) |
| No compensate propagation | `ActivityServiceImpl:277` | Compensate только для scopeCompleted, не для nested |

## Gateways

| Риск | Файл:Строка | Описание |
|---|---|---|
| Inclusive join BFS | `ActivityServiceImpl:856-888` | `findInclusiveJoin()` BFS обходит весь граф — O(V+E) |
| Parallel join token reuse | `ActivityServiceImpl:736-747` | `oldTokenId` используется для join — если有多 веток на разных токенах, `getToken(tokenId).getParentId()` может вернуть неожиданный parent |
| Exclusive gateway no match | `ActivityServiceImpl:915-920` | Если ни одно условие не истинно и нет default → `IllegalStateException` → инцидент |

## Timers

| Риск | Файл:Строка | Описание |
|---|---|---|
| No leader-election | `TimerScheduler:26-42` | `@Scheduled` без блокировки — дубли при кластере |
| TimeCycle missing | `BpmnParseServiceImpl:109-116` | Только `timeDate`/`timeDuration`, нет `timeCycle` |
| Boundary timer no cancel | `ActivityServiceImpl:1325-1343` | Boundary timer создаёт `TimerJob`, но не отменяет предыдущий при повторном входе в activity |
| Timer re-fire guard | `ActivityServiceImpl:1380-1384` | `lockAndReload` + status check — корректно предотвращает двойное срабатывание |

## Variables

| Риск | Файл:Строка | Описание |
|---|---|---|
| No type for DOUBLE/DATE | `ScriptServiceImpl:49-55` | Конвертирует только LONG/BOOLEAN, остальное → String |
| Scoped variables | `DBServiceImpl:242-302` | ✅ Реализовано: root scope (scope_id IS NULL) + local scope (activityId). Input-локали не протекают на экземпляр |
| No variable versioning | `DBServiceImpl:278-297` | `setVariables` upsert по name — нет истории изменений |
| ProcessVariable type mismatch | `ServiceTaskEnqueueServiceImpl:49` | Использует scoped view: `getVariables(processInstanceId, serviceTaskId)` |

---

# Concurrency And Transactions

## Race Conditions

| Проблема | Файл:Строка | Описание |
|---|---|---|
| `containsKey` + `get` race | `BpmnServiceImpl:32-37` | `ConcurrentHashMap.containsKey()` + `get()` не атомарны, два потока могут одновременно загрузить модель |
| Timer double-fire | `TimerScheduler:26-42` | Без leader-election, несколько инстансов опрашивают одни и те же таймеры |
| Message correlation race | `ActivityServiceImpl:1477-1528` | `correlateMessage` ищет subscriptions, затем помечает consumed — нет атомарности между поиском и пометкой |
| Conditional event re-trigger | `ActivityServiceImpl:497-531` | `evaluatingConditionals` ThreadLocal guard — работает только в пределах одного потока |

## Deadlock

| Проблема | Файл:Строка | Описание |
|---|---|---|
| child→parent lock order | `ActivityServiceImpl:1790-1791` | `lockProcessInstance(parentPiId)` после `completeProcessInstance(childPiId)` — child→parent ordering, deadlock-free |
| parent→child lock | `ActivityServiceImpl:1625` | `resolveIncident` → `lockProcessInstance` → `execute` — если execute вызовет call activity, будет child→parent lock顺序 |
| No lock timeout | `DBServiceImpl:194` | `findByIdForUpdate()` без timeout — может зависнуть при долгой блокировке |

## Optimistic/Pessimistic Locking

| Проблема | Файл:Строка | Описание |
|---|---|---|
| Pessimistic lock только на PI | `DBServiceImpl:194` | `findByIdForUpdate` только на process instance, не на activity/token |
| Нет optimistic lock | Везде | Нет `@Version` на entity — нет optimistic locking |
| SELECT FOR UPDATE без timeout | `DBServiceImpl:194` | `findByIdForUpdate()` — по умолчанию ждёт бесконечно |

## ThreadLocal

| Проблема | Файл:Строка | Описание |
|---|---|---|
| `executionDepth` ThreadLocal | `ActivityServiceImpl:85` | Значение не сбрасывается при исключении в верхнем фрейме (finally корректен) |
| `evaluatingConditionals` ThreadLocal | `ActivityServiceImpl:87` | Guard от рекурсии работает только в одном потоке |
| Virtual threads risk | `ActivityServiceImpl:85` | При virtual threads (Spring Boot 4.x default) ThreadLocal может давать утечку если тред переиспользуется |

## Spring Transactions

| Проблема | Файл:Строка | Описание |
|---|---|---|
| `@Transactional` на REST layer | `RuntimeResource:24-45` | Каждый REST-вызов — отдельная транзакция, но вся логика исполнения внутри одной |
| `@Transactional` нет на ActivityService | `ActivityServiceImpl` | Вся логика исполнения без транзакционной обёртки — зависит от вызывающего слоя |
| Long transaction | `ActivityServiceImpl:1655-1674` | `startProcessInstanceAt` создаёт PI, token, подписки, execute — всё в одной транзакции |
| Timer executor транзакция | `TimerJobExecutor:24` | `@Transactional` на `fire()` — каждый таймер в отдельной транзакции (корректно) |
| Service task afterCommit | `ServiceTaskEnqueueServiceImpl:34-68` | Публикация события после коммита — RabbitMQ недоступность не откатит транзакцию |

---

# Technical Debt

## P1 — Критично

| # | Проблема | Файл:Строка | Описание |
|---|---|---|---|
| 1 | NPE в processCallActivity | `ActivityServiceImpl:649` | Нет null-проверки extensions/callActivityExtension/processId |
| 2 | NPE в finishBranch (parent element) | `ActivityServiceImpl:1805` | getElement() возвращает null → NPE в proceedToOutgoing |
| 3 | Нет аутентификации | REST layer | Все эндпоинты открыты |
| 4 | Single-instance only | `TimerScheduler:26` | Таймеры без leader-election |
| 5 | `propagateAllChildVariables` игнорируется | `ActivityServiceImpl:1800-1801` | Variable propagation не учитывает флаг |

## P2 — Важно

| # | Проблема | Файл:Строка | Описание |
|---|---|---|---|
| 6 | Multi-instance без scoped vars | `ActivityServiceImpl:1151-1166` | `inputElement`/`outputCollection` парсятся, но scoped-изоляция `loopCounter` не реализована |
| 7 | Compensation в scope подпроцесса | `ActivityServiceImpl:216` | Targeted compensation реализована; компенсация в scope встроенного подпроцесса — отдельный заход |
| 8 | TimeCycle не парсится | `BpmnParseServiceImpl:109-116` | Только `timeDate`/`timeDuration` |
| 9 | Нет DMN versioning | `DmnServiceImpl:52` | Перезапись при повторном деплое |
| 10 | Нет `@Version` optimistic lock | Все entity | Нет optimistic locking |
| 11 | SELECT FOR UPDATE без timeout | `DBServiceImpl:194` | Потенциальный deadlock |
| 12 | CORS полностью открыт | README:253 | Нет ограничений |
| 13 | ProcessVariable type String по умолчанию | `ScriptServiceImpl:54` | DOUBLE/DATE → String, ломает FEEL |

## P3 — Желательно

| # | Проблема | Файл:Строка | Описание |
|---|---|---|---|
| 14 | `SneakyThrows` в ScriptService | `ScriptServiceImpl:29` | Скрывает checked exceptions |
| 15 | Reflection fallback в collectionSize | `ActivityServiceImpl:1207-1213` | Хрупко для Scala collections |
| 16 | processFlow перечитывает PI + BPMN | `ActivityServiceImpl:1706-1715` | N+1 reads на каждый sequence flow |
| 17 | JDK version mismatch | `pom.xml` | `java.version=17` в engine POM, но собирается на JDK 21 |
| 18 | No `timeCycle` in timer start | `BpmnParseServiceImpl:104-117` | Timer start: только date/duration |

---

# Refactoring Recommendations

## 1. Call Activity — null-safe chain

**Файл**: `ActivityServiceImpl:649`

Текущий код:
```java
String key = bpmnElement.getExtensions().getCallActivityExtension().getProcessId();
```

Предложение: добавить null-checks с информативными ошибками:
```java
CallActivityExtensionModel ext = Optional.ofNullable(bpmnElement.getExtensions())
    .map(BpmnElementExtensionModel::getCallActivityExtension)
    .orElseThrow(() -> new EngineException("Call activity '" + bpmnElement.getId() + "' has no zeebe:calledElement"));
String key = Optional.ofNullable(ext.getProcessId())
    .filter(s -> !s.isBlank())
    .orElseThrow(() -> new EngineException("Call activity '" + bpmnElement.getId() + "' has no processId"));
```

## 2. finishBranch — defensive getElement

**Файл**: `ActivityServiceImpl:1805`

Предложение:
```java
BpmnElementModel parentBpmnElement = parentBpmn.getElement(parentActivity.getBpmnElementId());
if (parentBpmnElement == null) {
    throw new EngineException("Call activity element '" + parentActivity.getBpmnElementId() + "' not found in parent definition");
}
```

## 3. Variable propagation флаг

**Файл**: `ActivityServiceImpl:1800-1801`

Предложение: проверить `propagateAllChildVariables`:
```java
CallActivityExtensionModel callExt = /* resolve from parent element */;
if (callExt == null || Boolean.TRUE.equals(callExt.getPropagateAllChildVariables())) {
    List<ProcessVariable> variables = dbService.getVariables(processInstanceId);
    dbService.setVariables(parentProcessInstanceId, variables);
}
```

## 4. Транзакционный контур

**Файл**: `ActivityServiceImpl`

Предложение: вынести ключевые entry points (`startProcessInstance`, `completeServiceTask`, `completeUserTask`, `signal`, `resolveIncident`) в отдельный `@Transactional` сервис-слой, чтобы транзакция не зависела от REST-контроллера.

## 5. BpmnService thread-safety

**Файл**: `BpmnServiceImpl:31-37`

Предложение: использовать `computeIfAbsent`:
```java
return models.computeIfAbsent(id, uuid -> {
    String bpmn = fileService.getFileBytes(uuid);
    if (bpmn == null) throw new EngineException("BPMN file not found for definition " + uuid);
    return bpmnParseService.parse(bpmn);
});
```

## 6. Variable type system

**Файл**: `ScriptServiceImpl:49-55`

Предложение: поддержать DOUBLE/DATE типы, или хотя бы логировать предупреждение о потере типа.

## 7. DMN versioning

**Файл**: `DmnServiceImpl:42-55`

Предложение: добавить version + unique constraint на `(decisionId, version)` или использовать latest-version паттерн.

---

# Claude Tasks

## Task 1: Fix NPE chain in processCallActivity

**Цель**: Устранить критический NPE при вызове call activity с некорректными/отсутствующими extension elements.

**Какие файлы изменить**:
- `zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/service/impl/ActivityServiceImpl.java` (строка 649)
- `zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/service/impl/BpmnParseServiceImpl.java` (строка 928)

**Что исправить**:
1. В `BpmnParseServiceImpl:928-936` — если `calledElement` == null или `calledElement.getProcessId()` == null, выбрасывать `BpmnParseException` с информативным сообщением вместо silent null.
2. В `ActivityServiceImpl:649` — заменить голый chain вызов на null-safe цепочку с `Optional` + `orElseThrow(EngineException)`.

**Критерии приемки**:
- BPMN XML с `<callActivity>` без `<zeebe:calledElement>` → ошибка парсинга с сообщением "Call activity 'X' has no zeebe:calledElement"
- BPMN XML с `<zeebe:calledElement>` без `processId` → ошибка парсинга с сообщением "Call activity 'X' has no processId"
- Корректный call activity → работает как раньше

---

## Task 2: Fix NPE in finishBranch for parent call activity element

**Цель**: Защитить `finishBranch` от NPE при возврате из дочернего процесса в родительский.

**Какие файлы изменить**:
- `zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/service/impl/ActivityServiceImpl.java` (строка 1805)

**Что исправить**:
1. Добавить null-check после `parentBpmn.getElement(parentActivity.getBpmnElementId())`:
   ```java
   BpmnElementModel parentBpmnElement = parentBpmn.getElement(parentActivity.getBpmnElementId());
   if (parentBpmnElement == null) {
       throw new EngineException("Call activity element '" + parentActivity.getBpmnElementId()
           + "' not found in parent process definition");
   }
   ```

**Критерии приемки**:
- Если parentBpmnElement == null → EngineException с информативным сообщением (не NPE)
- Normal flow → работает как раньше

---

## Task 3: Implement propagateAllChildVariables flag

**Цель**: Реализовать поддержку флага `propagateAllChildVariables` из `zeebe:calledElement`.

**Какие файлы изменить**:
- `zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/service/impl/ActivityServiceImpl.java` (строки 1800-1801)

**Что исправить**:
1. В `finishBranch`, перед копированием переменных child→parent, получить `CallActivityExtensionModel` из parent element.
2. Если `propagateAllChildVariables == false` — пропустить `dbService.setVariables(parentProcessInstanceId, variables)`.

**Критерии приемки**:
- `propagateAllChildVariables="true"` (или не задано) → переменные копируются (default behavior)
- `propagateAllChildVariables="false"` → переменные НЕ копируются
- Интеграционный тест для каждого случая

---

## Task 4: Add null-safe getElement in proceedToOutgoing and execute

**Цель**: Защитить `proceedToOutgoing` и `execute` от NPE при отсутствии элемента/flow в модели.

**Какие файлы изменить**:
- `zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/service/impl/ActivityServiceImpl.java` (строки 156-158, 609)

**Что исправить**:
1. В `proceedToOutgoing:156-157` — добавить null-check для `flow` и `target`:
   ```java
   BpmnFlowModel flow = bpmn.getFlow(outgoing);
   if (flow == null) {
       throw new EngineException("Sequence flow '" + outgoing + "' not found in process definition");
   }
   BpmnElementModel target = bpmn.getElement(flow.getTargetRef());
   if (target == null) {
       throw new EngineException("Target element '" + flow.getTargetRef() + "' not found in process definition");
   }
   ```
2. В `execute:609` — добавить null-check для `element`:
   ```java
   if (element == null) {
       throw new EngineException("Element '" + bpmnElementId + "' not found in process definition");
   }
   ```

**Критерии приемки**:
- Отсутствующий flow → EngineException с ID flow
- Отсутствующий target → EngineException с ID target
- Отсутствующий element → EngineException с element ID

---

## Task 5: Fix BpmnServiceImpl thread-safety with computeIfAbsent

**Цель**: Устранить race condition и NPE при параллельной загрузке моделей BPMN.

**Какие файлы изменить**:
- `zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/service/impl/BpmnServiceImpl.java` (строки 31-38)

**Что исправить**:
1. Заменить `containsKey` + `get` на `computeIfAbsent`:
   ```java
   @Override
   public BpmnProcessDefinitionModel getProcessDefinitionModelById(UUID id) {
       return models.computeIfAbsent(id, uuid -> {
           String bpmn = fileService.getFileBytes(uuid);
           if (bpmn == null) {
               throw new EngineException("BPMN file not found for definition " + uuid);
           }
           return bpmnParseService.parse(bpmn);
       });
   }
   ```

**Критерии приемки**:
- Два потока одновременно запрашивают одну модель → модель загружается ровно один раз
- Файл не найден → EngineException (не NPE)

---

## Task 6: Add timeCycle support to timer parsing

**Цель**: Поддержать повторяющиеся таймеры (`timeCycle`), как в Camunda 8.

**Какие файлы изменить**:
- `zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/bpmn/xml/BpmnTimerEventDefinitionModel.java` (добавить поле `timeCycle`)
- `zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/bpmn/model/TimerEventExtensionModel.java` (добавить тип CYCLE + expression)
- `zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/bpmn/model/TimerEventType.java` (добавить CYCLE)
- `zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/service/impl/BpmnParseServiceImpl.java` (строки 109-116, 849-860)
- `zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/service/impl/ActivityServiceImpl.java` (метод `computeDueAt`)

**Что исправить**:
1. Добавить XML-поле `timeCycle` в модель таймерного определения
2. Добавить `TimerEventType.CYCLE`
3. В парсере обработать `timeCycle` (вычислить next fire time)
4. В `computeDueAt` обработать тип CYCLE

**Критерии приемки**:
- BPMN с `<timeCycle>R/1H</timeCycle>` → таймер срабатывает каждые 30 минут
- `timeDate` и `timeDuration` работают как раньше

---

## Task 7: Add DMN versioning

**Цель**: Реализовать версионирование DMN определений.

**Какие файлы изменить**:
- `zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/entity/DmnDefinitionEntity.java` (добавить `version`)
- `zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/repository/DmnDefinitionRepository.java` (обновить запросы)
- `zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/service/impl/DmnServiceImpl.java` (строки 42-55, 58-66)

**Что исправить**:
1. Добавить поле `version` в `DmnDefinitionEntity`
2. При деплое: инкрементировать версию вместо перезаписи
3. При evaluate: использовать latest version
4. Liquibase миграция: добавить колонку `version`

**Критерии приемки**:
- Повторный деплой DMN с тем же `decisionId` → новая версия, старая сохраняется
- Evaluate используется latest version
- Обратная совместимость: существующие данные работают

---

## Task 8: Fix processCallActivity NPE — defensive null-checks

**Цель**: Добавить информативные ошибки для всех edge cases в processCallActivity.

**Какие файлы изменить**:
- `zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/service/impl/ActivityServiceImpl.java` (строки 649-654)

**Что исправить**:
```java
private void processCallActivity(UUID processInstanceId, UUID tokenId,
        BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
    UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);

    List<ProcessVariable> variables = dbService.getVariables(processInstanceId);

    // null-safe extraction of called process key
    String key = Optional.ofNullable(bpmnElement.getExtensions())
        .map(BpmnElementExtensionModel::getCallActivityExtension)
        .map(CallActivityExtensionModel::getProcessId)
        .filter(s -> !s.isBlank())
        .orElseThrow(() -> new EngineException(
            "Call activity '" + bpmnElement.getId() + "' has no zeebe:calledElement processId"));

    Integer version = dbService.getMaxProcessDefinitionVersionByKey(key);
    if (version == 0) {
        throw new EngineException(
            "Call activity '" + bpmnElement.getId()
            + "' references process '" + key + "' which has no deployed definition");
    }

    ProcessDefinition pd = dbService.getProcessDefinition(key, version);
    UUID processDefinitionId = pd.getId();

    startProcessInstance(activityId, processDefinitionId, variables);
}
```

**Критерии приемки**:
- Call activity без processId → `EngineException("Call activity 'X' has no zeebe:calledElement processId")`
- Call activity с несуществующим processKey → `EngineException("...references process 'Y' which has no deployed definition")`
- Happy path → работает как раньше

---

## Task 9: Add @Transactional to core ActivityService entry points

**Цель**: Гарантировать транзакционность операций исполнения, независимо от вызывающего слоя.

**Какие файлы изменить**:
- `zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/service/impl/ActivityServiceImpl.java`

**Что исправить**:
1. Добавить `@Transactional` на публичные методы:
   - `startProcessInstance`
   - `startProcessInstanceFromStartEvent`
   - `completeServiceTask`
   - `completeUserTask`
   - `signal`
   - `fireBoundaryTimer`
   - `fireEventSubprocessTimer`
   - `correlateMessage` (2 варианта)
   - `resolveIncident`

**Критерии приемки**:
- Все entry points выполняются в одной транзакции
- При ошибке — транзакция откатывается
- Timer/signal/message callbacks работают в собственных транзакциях (TimerJobExecutor уже имеет @Transactional)

---

## Task 10: Add process variable type DOUBLE support

**Цель**: Расширить поддержку типов переменных в FEEL-вычислениях.

**Какие файлы изменить**:
- `zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/service/impl/ScriptServiceImpl.java` (строки 49-55)
- `zorrobpm-contract/src/main/java/com/zorrodev/bpm/contract/model/ProcessVariableType.java` (добавить DOUBLE)
- `zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/service/impl/DBServiceImpl.java` (метод `toProcessVariable`)

**Что исправить**:
1. Добавить `DOUBLE` в `ProcessVariableType` enum
2. В `ScriptServiceImpl:49-55` — обработать DOUBLE: `Double.valueOf(variable.getValue())`
3. В `DBServiceImpl:264-270` — обработать DOUBLE при чтении из БД
4. В `ActivityServiceImpl:1007-1021` — `toProcessVariable` обрабатывать Double

**Критерии приемки**:
- Variable с типом DOUBLE корректно передаётся в FEEL-выражения
- Double результат script task сохраняется как DOUBLE
- Обратная совместимость: LONG/BOOLEAN/STRING работают как раньше
