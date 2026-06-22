# Аудит совместимости BPMN-конструкций с Camunda 8

Документ оценивает, насколько BPMN-конструкции, **парсимые и исполняемые движком ZorroBPM**, совместимы
с **Camunda 8 (Zeebe)** — с точки зрения переносимости моделей (один и тот же `.bpmn` должен и парситься
здесь, и деплоиться в Camunda 8) и семантики исполнения.

> **Метод.** Список конструкций получен из фактически парсимых элементов/атрибутов
> (`zorrobpm-engine/.../bpmn/xml/*`, `dmn/xml/*`) и обработчиков исполнения (`ActivityServiceImpl`).
> Namespace модели — BPMN 2.0 (`http://www.omg.org/spec/BPMN/20100524/MODEL`); расширения — Zeebe
> (`http://camunda.org/schema/zeebe/1.0`), что соответствует Camunda 8.
>
> **Легенда статусов:**
> - ✅ **Совместимо** — конструкция и её модель поддерживаются Camunda 8; XML переносится без изменений.
> - ⚠️ **Частично / нестандартно** — поведение есть, но модель отличается от того, как это задаёт Camunda 8
>   (тот же `.bpmn` в Camunda 8 не задеплоится или будет проигнорирован без правок).
> - ❌ **Не поддерживается в Camunda 8** — конструкция стандартна для BPMN 2.0, но Camunda 8 её не исполняет.
>
> Где оценка опирается на версионную матрицу Camunda 8 и не подтверждена тестом в этом репозитории — помечено
> «*гипотеза*».

---

## 1. События (events)

| Конструкция | Статус | Описание проблемы | Рекомендация |
|---|---|---|---|
| Start / End (none) | ✅ Совместимо | — | — |
| Terminate end (`terminateEventDefinition`) | ✅ Совместимо | — | — |
| Message start / catch / throw / boundary (`messageEventDefinition`, `message`, `messageRef`) | ✅ Совместимо | Camunda 8 поддерживает message-события | — |
| Timer start / catch / boundary (`timerEventDefinition`, `timeDate`, `timeDuration`, `timeCycle`) | ✅ Совместимо | date/duration; **`timeCycle`** — ISO `R[n]/<duration>` и **cron** (Spring 6-field). **Повтор** (`R/<duration>` или cron): timer-start перепланирует следующий запуск; **non-interrupting boundary** перевзводится и срабатывает каждый цикл пока хост активен («напоминание каждые N»). **Отложено**: ограниченный повтор `R<n>` (нужен счётчик) | Счётчик повторов в timer_jobs — отдельный заход |
| Error end / boundary (`errorEventDefinition`, `error`, `errorCode`) | ✅ Совместимо | — | — |
| Signal start / catch / throw / boundary (`signalEventDefinition`, `signal`) | ✅ Совместимо | C8 поддерживает signal (8.3+) | — |
| Escalation throw / end / boundary (`escalationEventDefinition`, `escalation`) | ✅ Совместимо | C8 поддерживает escalation (8.2+) | — |
| Link catch / throw (`linkEventDefinition`) | ✅ Совместимо | C8 поддерживает link-события (8.2+) | — |
| Compensation boundary / throw (`compensateEventDefinition`, `association`) | ✅ Совместимо | Compensation boundary + intermediate throw, **compensate-all и targeted** (`activityRef`), откат завершённых активностей в обратном порядке. Компенсация в scope **встроенного подпроцесса** — отдельный заход | — |
| **Conditional start / catch / boundary** (`conditionalEventDefinition`, `condition`) | ❌ Не поддерживается в C8 | **Camunda 8 не поддерживает conditional-события** ни в каком виде. Этот `.bpmn` в C8 не задеплоится | Не использовать в моделях, предназначенных для C8; для C8 заменять на шлюз с FEEL-условием по данным + таймер/сообщение |
| **Cancel end / boundary** (`cancelEventDefinition`) | ❌ Не поддерживается в C8 | Cancel-события привязаны к transaction-подпроцессу, которого нет в C8 | Заменять на error-события + явную компенсацию |

---

## 2. Задачи (tasks)

| Конструкция | Статус | Описание проблемы | Рекомендация |
|---|---|---|---|
| Service task (`zeebe:taskDefinition type/retries`) | ✅ Совместимо | C8-нативная модель job-worker | — |
| User task (`zeebe:assignmentDefinition`, `zeebe:formDefinition`, `zeebe:userTask`) | ✅ Совместимо | Job-worker user task и C8-нативный `zeebe:userTask` (маркер) — обе формы парсятся и исполняются (паркуется, завершается через API) | — |
| Receive task (`receiveTask` + `messageRef`) | ✅ Совместимо | — | — |
| Business rule task — DMN (`zeebe:calledDecision decisionId/resultVariable`) | ✅ Совместимо (модель) | Модель C8-нативная. Исполнение — **собственным DMN-движком** (не Zeebe DMN), результат эквивалентен для стандартных таблиц | Для гарантий — сверять edge-кейсы DMN/FEEL с реальным C8 |
| **Script task** (`<zeebe:script>`, `<zeebe:taskDefinition>` или inline `<bpmn:script>`) | ✅ Совместимо | C8-нативный `<zeebe:script expression=… resultVariable=…>` (FEEL), **`<zeebe:taskDefinition>`** (job worker — исполняется как service task) и BPMN-стандартный inline `<bpmn:script>` | — |
| **Business rule task — FEEL** (`zeebe:script` на `businessRuleTask`) | ⚠️ Нестандартно | C8 на `businessRuleTask` использует `zeebe:calledDecision` или `zeebe:taskDefinition`, но **не** `zeebe:script`. Это собственное расширение проекта | Считать проектным расширением; в C8-моделях использовать DMN-режим |
| **Send task** (`zeebe:taskDefinition` или `messageRef`) | ✅ Совместимо | Camunda 8 форма с `zeebe:taskDefinition` исполняется как job worker (service task); BPMN-стандартная `messageRef`-форма — message throw | — |
| Manual / Undefined task | — (не парсится) | Не используется движком | При необходимости трактовать как pass-through |

---

## 3. Шлюзы (gateways)

| Конструкция | Статус | Описание проблемы | Рекомендация |
|---|---|---|---|
| Exclusive gateway (`exclusiveGateway`, `default`, FEEL `conditionExpression`) | ✅ Совместимо | — | — |
| Parallel gateway | ✅ Совместимо | — | — |
| Inclusive gateway | ✅ Совместимо | C8 поддерживает inclusive (8.1+) | — |
| Event-based gateway | ✅ Совместимо | — | — |

---

## 4. Подпроцессы и активности

| Конструкция | Статус | Описание проблемы | Рекомендация |
|---|---|---|---|
| Call activity (`zeebe:calledElement processId/bindingType/propagateAllChildVariables`) | ✅ Совместимо | C8-нативная модель; `propagateAllChildVariables` учитывается (default `true` копирует переменные ребёнка в родителя, `false` — нет) | — |
| Embedded subprocess (`subProcess`) | ✅ Совместимо | — | — |
| Event subprocess (`subProcess triggeredByEvent="true"`) | ✅ Совместимо | Триггеры **message / signal / error / timer** (message — interrupting и non-interrupting; signal/error/timer — interrupting). Event-subprocess **внутри встроенного подпроцесса** (не top-level) — отдельный заход | — |
| **Transaction subprocess** (`<bpmn:transaction>`) | ❌ Не поддерживается в C8 | **Camunda 8 не поддерживает transaction-подпроцесс** | Заменять на embedded subprocess + явная компенсация/error-обработка |
| **Multi-instance** (`zeebe:loopCharacteristics inputCollection`/`inputElement`/`outputCollection`/`outputElement` или `loopCardinality`) | ✅ Совместимо | На **user и service task** (доминирующий C8-паттерн «для каждого элемента — вызвать сервис»). Count — из `inputCollection`/`loopCardinality`; `completionCondition`; per-instance `inputElement`+`loopCounter` (scoped); агрегирование `outputElement`→`outputCollection` (JSON-список). Параллельный и последовательный | — |

---

## 5. Маппинги данных и расширения

| Конструкция | Статус | Описание проблемы | Рекомендация |
|---|---|---|---|
| Типы переменных (`STRING`/`LONG`/**`DOUBLE`**/`BOOLEAN`/`UUID`/**`JSON`**) | ✅ Совместимо | **Десятичные (`DOUBLE`)** и **JSON-объекты/списки** входят в FEEL/DMN как число / Map/List (доступ к `order.total`, итерация); FEEL-выражение, *возвращающее* структуру (script task/IO/DMN), сохраняется как `JSON` (Scala/Java-коллекция → JSON рекурсивно). Полный round-trip объект↔FEEL | — |
| IO mappings (`zeebe:ioMapping` input/output) | ✅ Совместимо | C8-нативно со **scoped-переменными**: input-маппинги локальны для активности (не протекают на экземпляр), output-маппинги пропагируются в родительский scope; локальные переменные удаляются по завершении задачи | — |
| Message correlation key (`zeebe:subscription correlationKey` на `<message>`) | ✅ Совместимо | C8-нативно | — |
| Sequence flow condition (`conditionExpression`, FEEL) | ✅ Совместимо | — | — |
| Properties (`zeebe:properties/property`) | ✅ Совместимо | Парсится, семантика проектная | — |
| DMN-таблицы (DMN 1.3, FEEL) | ✅ Совместимо (формат) | Формат DMN 1.3 C8-совместим; **версионирование** (повторный деплой `decisionId` → новая версия, evaluate берёт latest, история сохраняется). Деплой — через сервис, не Zeebe-ресурс | Для C8 деплой DMN отдельным ресурсом; учесть при интеграции |

---

## Итоговая матрица совместимости Camunda 8

| Категория | Конструкции | Статус |
|---|---|---|
| **Полностью совместимо** | Start/End/Terminate, Message/Timer/Error/Signal/Escalation/Link события (start/catch/throw/boundary), Exclusive/Parallel/Inclusive/Event-based шлюзы, Service/User (вкл. `zeebe:userTask`)/Receive/**Send** task (`zeebe:taskDefinition`), **Script task** (`zeebe:script` + inline), Business rule task (DMN), Call activity, Embedded & Event subprocess (message/signal/error/timer), IO mappings (scoped), **Compensation** (compensate-all + targeted), correlation key, FEEL-условия | ✅ |
| **Частично / нестандартно** (поведение есть, модель расходится с C8) | Multi-instance (count + per-instance `inputElement`/`loopCounter`; агрегирование `outputCollection` — позже), Business rule FEEL-режим (`zeebe:script` — проектное расширение), компенсация в scope подпроцесса (отдельный заход) | ⚠️ |
| **Не поддерживается в Camunda 8** (стандарт BPMN, но C8 не исполняет) | Conditional события (start/catch/boundary), Transaction subprocess, Cancel события (end/boundary) | ❌ |

### Сводные рекомендации

1. **Для моделей, которые должны переноситься в Camunda 8 без правок** — избегать только ❌-конструкций
   (conditional, transaction, cancel). Все ⚠️-расхождения по нотации закрыты: `zeebe:script`,
   `zeebe:loopCharacteristics`, `zeebe:taskDefinition` (send task), `zeebe:userTask`.
2. **Дорожная карта паритета с C8**: ~~`zeebe:script`/`zeebe:loopCharacteristics`~~ ✅,
   ~~scoped IO-mappings~~ ✅, ~~триггеры event subprocess (timer/error/signal)~~ ✅,
   ~~targeted-компенсация~~ ✅, ~~send task job-worker~~ ✅, ~~`zeebe:userTask`~~ ✅.
   Осталось (требует list-типизированных переменных / отдельных заходов): per-instance
   `inputElement`/`outputCollection` для multi-instance, компенсация в scope подпроцесса, timer cycle.
3. **Расхождения по дизайну оставить осознанно**: conditional/transaction/cancel — это надстройка над C8
   (полезна для процессов вне C8); поведение DMN — собственным движком на том же FEEL (поведенчески
   эквивалентно для стандартных таблиц).
