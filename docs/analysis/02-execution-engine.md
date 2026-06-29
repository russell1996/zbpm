# 02 — Execution Engine (as-is)

## Модель исполнения
**Токенный обход графа.** Определение процесса = направленный граф (`BpmnProcessDefinitionModel`,
элементы + потоки). Токен (`tokens`) движется по элементам; каждый шаг создаёт строку `activities`
(аудит-история + текущее состояние). Обход **синхронный и рекурсивный** в потоке вызывающего, внутри
одной транзакции (`execute` → handler → `proceedToOutgoing` → `execute` …).

- **`execute(pi, token, bpmn, element)`** [:633] — диспетчер: `handlers.get(type).handle(...)`. Гард глубины
  `executionDepth` (ThreadLocal, лимит `maxExecutionDepth`) против безконечных циклов/рекурсии call activity (**P8**).
  Нет хендлера → инцидент «Unsupported BPMN element type», токен не теряется (**P4**). Любое исключение
  хендлера → `raiseIncident` (паркует токен), а не откат всего процесса (**P4**).
- **`proceedToOutgoing`** [:152] — проход по всем исходящим потокам и `execute` цели каждого.
- **`processFlow`** [:1993] — материализует прохождение потока (строка-активность), при условном потоке
  вычисляет FEEL-условие; при прибытии на parallel/inclusive-join записывает arrival (`recordParallelGatewayArrival`).

## Диспетчер (EnumMap)
`createHandlers()` [:92] — `EnumMap<BpmnElementType, ElementHandler>`. 47 типов элементов
(`BpmnElementType`). Покрыты: start (none/message/timer/signal), end (none/terminate/error/escalation),
throw/catch (intermediate/message/timer/signal/link/conditional/compensation), cancel-end, все 4 шлюза,
service/user/send/receive/script/business-rule task, call activity, sub-process. (**P1**.)

## Токены и скоупы
- `createToken(parentId)` — дочерний токен (split); `createToken(parentId, scopeActivityId)` — токен,
  привязанный к scope активности (subprocess). Токены образуют родительскую иерархию.
- **Embedded subprocess** [`processSubProcess` :712]: контейнер-активность + дочерний scope-токен → исполняется
  вложенный start; на end-событии (`finishBranch` :2061) контейнер завершается и **родительский** токен идёт дальше.
- **Call activity** [`processCallActivity` :680]: стартует новый process instance с `parentActivityId`;
  null-safe (нет `calledElement`/не задеплоен target → информативный инцидент, не NPE).

## Шлюзы
- **Exclusive** [:939]: условия по порядку, первый true побеждает; иначе default; иначе (нет default) — инцидент (**P4**). 1-in→1-out: pass-through.
- **Parallel** [:758]: split (1-in,>1-out) — один общий дочерний токен на все ветки. Join (>1-in): арривалы
  пишутся в `parallel_gateways`, fire только когда `arrived ⊇ все incoming`; затем `clearParallelGatewayArrivals`
  (повторный проход через join ждёт свежий комплект — поддержка циклов). Continuation на родительском токене (`token.getParentId()`).
- **Inclusive** [:813]: split активирует ветки с true-условием (или default); `findInclusiveJoin` (forward-BFS,
  первый inclusive-join с >1 incoming) + `recordInclusiveExpected(count)`; join ждёт ровно `expected` арривалов.
  **M2:** поддержан канонический single-split→single-join; неканонические/вложенные топологии хрупки.
- **Event-based** [:731]: паркует и ждёт первого из последующих catch-событий.

## Multi-instance
`enterMultiInstance` (для user/service task; флаг `isMultiInstance` :1299). Поддержка
`inputCollection`→размер коллекции либо `loopCardinality`, per-instance `inputElement`+`loopCounter`,
parallel/sequential, `completionCondition`, агрегация output (`aggregateMultiInstanceOutput` :1273).
`multiInstanceContinue` решает, остались ли инстансы (parallel) / стартовать следующий (sequential).

## IO-mappings (C8-семантика) — **P5**
`applyIoMappings` [:1208]: inputs на активации → **локальный** scope (`activityId`); outputs на завершении → **корень**
(process-instance). Оба вычисляются по merged-view (root+local); локальные input-переменные **не протекают**
в инстанс — `deleteVariables(scopeId)` чистит их при завершении задачи.

## Завершение/инциденты
- `completeServiceTask`/`completeUserTask` — статус-гарды (только CREATED/IN_PROGRESS), идемпотентны к
  редоставке/boundary-прерыванию/уже-COMPLETED/ERROR (**P2**); затем IO-output, MI-агрегация, чистка scope,
  `proceedToOutgoing`, `triggerConditionalEvents`.
- `failServiceTask` [:1171] — Camunda `failJob`: явный `retries` ставит бюджет (0 → инцидент сразу), иначе −1;
  пока бюджет>0 — повторный enqueue; иначе ERROR + инцидент с сообщением воркера.
- `resolveIncident` [:1904] — лок инстанса, применяет переменные, закрывает инцидент, **отменяет** старую
  ERROR-активность (чтобы поздняя/дубль-completion её job была проигнорирована — анти-double-advance), `execute` заново.

## Bottlenecks/долг этого слоя
- **B1** синхронный рекурсивный обход в одной транзакции. **B2** пессимистичный лок per-instance.
- **B4** многократные `getVariables` полного набора за один шаг.
- Архитектурная развилка для HA: переход на async/job-based continuation — кандидат на ADR (см. [08](08-cluster-ha-security.md)).
