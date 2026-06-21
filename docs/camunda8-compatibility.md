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
| Timer start / catch / boundary (`timerEventDefinition`, `timeDate`, `timeDuration`) | ✅ Совместимо | C8 поддерживает date/duration/cycle | Cycle (`timeCycle`) здесь не парсится — добавить при необходимости |
| Error end / boundary (`errorEventDefinition`, `error`, `errorCode`) | ✅ Совместимо | — | — |
| Signal start / catch / throw / boundary (`signalEventDefinition`, `signal`) | ✅ Совместимо | C8 поддерживает signal (8.3+) | — |
| Escalation throw / end / boundary (`escalationEventDefinition`, `escalation`) | ✅ Совместимо | C8 поддерживает escalation (8.2+) | — |
| Link catch / throw (`linkEventDefinition`) | ✅ Совместимо | C8 поддерживает link-события (8.2+) | — |
| Compensation boundary / throw (`compensateEventDefinition`, `association`) | ⚠️ Частично | C8 поддерживает компенсацию с 8.5; есть ограничения (компенсация подпроцесса/области видимости). Здесь — compensate-all через intermediate throw, targeted (`activityRef`) и компенсация в подпроцессе отложены | Для переносимости в C8 ориентироваться на 8.5+; согласовать семантику targeted-компенсации с C8 |
| **Conditional start / catch / boundary** (`conditionalEventDefinition`, `condition`) | ❌ Не поддерживается в C8 | **Camunda 8 не поддерживает conditional-события** ни в каком виде. Этот `.bpmn` в C8 не задеплоится | Не использовать в моделях, предназначенных для C8; для C8 заменять на шлюз с FEEL-условием по данным + таймер/сообщение |
| **Cancel end / boundary** (`cancelEventDefinition`) | ❌ Не поддерживается в C8 | Cancel-события привязаны к transaction-подпроцессу, которого нет в C8 | Заменять на error-события + явную компенсацию |

---

## 2. Задачи (tasks)

| Конструкция | Статус | Описание проблемы | Рекомендация |
|---|---|---|---|
| Service task (`zeebe:taskDefinition type/retries`) | ✅ Совместимо | C8-нативная модель job-worker | — |
| User task (`zeebe:assignmentDefinition`, `zeebe:formDefinition`) | ✅ Совместимо | Соответствует C8 (job-worker user task). *Гипотеза:* «Camunda user task» (8.5+) с `zeebe:userTask` здесь не моделируется | При таргете на новый C8 user task добавить чтение `zeebe:userTask` |
| Receive task (`receiveTask` + `messageRef`) | ✅ Совместимо | — | — |
| Business rule task — DMN (`zeebe:calledDecision decisionId/resultVariable`) | ✅ Совместимо (модель) | Модель C8-нативная. Исполнение — **собственным DMN-движком** (не Zeebe DMN), результат эквивалентен для стандартных таблиц | Для гарантий — сверять edge-кейсы DMN/FEEL с реальным C8 |
| **Script task** (`<zeebe:script>` или inline `<bpmn:script>`) | ✅ Совместимо | Поддержаны оба способа: C8-нативный `<zeebe:script expression=… resultVariable=…>` в `extensionElements` (приоритет) и BPMN-стандартный inline `<bpmn:script>` (fallback) | — |
| **Business rule task — FEEL** (`zeebe:script` на `businessRuleTask`) | ⚠️ Нестандартно | C8 на `businessRuleTask` использует `zeebe:calledDecision` или `zeebe:taskDefinition`, но **не** `zeebe:script`. Это собственное расширение проекта | Считать проектным расширением; в C8-моделях использовать DMN-режим |
| **Send task** (`sendTask` + `messageRef`) | ⚠️ Частично | Здесь send task = message-throw по `messageRef`. *Гипотеза:* в C8 send task моделируется как job-worker (`zeebe:taskDefinition`), а не через `messageRef` | Для C8 заменять на message intermediate throw или service task |
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
| Call activity (`zeebe:calledElement processId/bindingType/propagateAllChildVariables`) | ✅ Совместимо | C8-нативная модель | — |
| Embedded subprocess (`subProcess`) | ✅ Совместимо | — | — |
| Event subprocess (`subProcess triggeredByEvent="true"`) | ✅ Совместимо (модель) | C8 поддерживает. Здесь исполняется только message-триггер (interrupting/non-interrupting); timer/error/signal-триггеры отложены | Дореализовать остальные триггеры для полного паритета с C8 |
| **Transaction subprocess** (`<bpmn:transaction>`) | ❌ Не поддерживается в C8 | **Camunda 8 не поддерживает transaction-подпроцесс** | Заменять на embedded subprocess + явная компенсация/error-обработка |
| **Multi-instance** (`zeebe:loopCharacteristics inputCollection` или `loopCardinality`) | ⚠️ Частично | Количество инстансов берётся из C8-нативного `<zeebe:loopCharacteristics inputCollection=…>` (размер коллекции) **или** из `loopCardinality`; `completionCondition` поддержан. **Отложено** (требует scoped-переменных, см. ниже): per-instance `inputElement`/`loopCounter` и агрегирование `outputCollection`/`outputElement` | Реализовать scoped-переменные → привязка `inputElement`/`outputCollection` (этап scoped vars) |

---

## 5. Маппинги данных и расширения

| Конструкция | Статус | Описание проблемы | Рекомендация |
|---|---|---|---|
| IO mappings (`zeebe:ioMapping` input/output) | ✅ Совместимо | C8-нативно. *Замечание:* в C8 переменные input/output **локальны** для scope элемента; здесь переменные плоские (маппинги «протекают» на экземпляр) | Реализовать scoped-переменные для полной семантической эквивалентности |
| Message correlation key (`zeebe:subscription correlationKey` на `<message>`) | ✅ Совместимо | C8-нативно | — |
| Sequence flow condition (`conditionExpression`, FEEL) | ✅ Совместимо | — | — |
| Properties (`zeebe:properties/property`) | ✅ Совместимо | Парсится, семантика проектная | — |
| DMN-таблицы (DMN 1.3, FEEL) | ✅ Совместимо (формат) | Формат DMN 1.3 C8-совместим; деплой здесь — через сервис, не Zeebe-ресурс | Для C8 деплой DMN отдельным ресурсом; здесь учесть при интеграции |

---

## Итоговая матрица совместимости Camunda 8

| Категория | Конструкции | Статус |
|---|---|---|
| **Полностью совместимо** | Start/End/Terminate, Message/Timer/Error/Signal/Escalation/Link события (start/catch/throw/boundary), Exclusive/Parallel/Inclusive/Event-based шлюзы, Service/User/Receive task, **Script task** (`zeebe:script` + inline), Business rule task (DMN), Call activity, Embedded & Event subprocess, IO mappings, correlation key, FEEL-условия | ✅ |
| **Частично / нестандартно** (поведение есть, модель расходится с C8) | Multi-instance (count из `zeebe:loopCharacteristics`/`loopCardinality`; `inputElement`/`outputCollection` — позже), Send task (`messageRef` vs job-worker), Business rule FEEL (`zeebe:script`), Compensation (compensate-all; targeted/в подпроцессе отложены), IO-mappings (плоские переменные вместо scoped) | ⚠️ |
| **Не поддерживается в Camunda 8** (стандарт BPMN, но C8 не исполняет) | Conditional события (start/catch/boundary), Transaction subprocess, Cancel события (end/boundary) | ❌ |

### Сводные рекомендации

1. **Для моделей, которые должны переноситься в Camunda 8 без правок** — избегать ❌-конструкций
   (conditional, transaction, cancel). ✅ `zeebe:script` (script task) и `zeebe:loopCharacteristics`
   (multi-instance, count) уже поддержаны. Оставшиеся ⚠️: send task → message intermediate throw / service task.
2. **Дорожная карта паритета с C8** (по приоритету): ~~чтение `zeebe:script`/`zeebe:loopCharacteristics`~~ ✅,
   scoped-переменные (для IO-mappings и per-instance `inputElement`/`outputCollection`),
   остальные триггеры event subprocess, targeted-компенсация.
3. **Расхождения по дизайну оставить осознанно**: conditional/transaction/cancel — это надстройка над C8
   (полезна для процессов вне C8); поведение DMN — собственным движком на том же FEEL (поведенчески
   эквивалентно для стандартных таблиц).
