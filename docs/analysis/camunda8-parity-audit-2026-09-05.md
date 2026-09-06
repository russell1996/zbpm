# Аудит паритета с Camunda 8 — полная ревизия эпика WO-C8

**Дата:** 2026-09-05
**Автор:** CTO (аудит проведён лично, по диску и git, не по отчётам исполнителя)
**Состояние master на момент аудита:** `25c6f08a`
**Предмет:** эпик WO-C8 «паритет BPMN/DMN-конструкций с Camunda 8» — 11 смёрженных WO, 1 в работе

---

## 0. Резюме для руководства

**Цель продукта:** ZorroBPM = «та же Camunda 8, только бесплатная». Практический критерий: диаграмма,
нарисованная в Camunda Modeler, деплоится в ZorroBPM **без переделки** и исполняется **с той же
семантикой**. Всё остальное — производное от этого критерия.

**Что подтверждено аудитом:**

| Показатель | Значение | Как проверено |
|---|---|---|
| Смёржено WO эпика | 11 из 11 заявленных | `git log master --merges --grep=WO-C8` — все 11 SHA существуют |
| Заявленные новые классы | 11 из 11 на диске | пофайловая проверка существования |
| Цепочки «парсинг → потребление» | 11 из 11 замкнуты | grep до точки реального использования, не до класса |
| Миграции БД | 083, 084, 085 — без дыр | `ls changesets/` |
| Характеризационная сюита | 44 теста | `grep "void "` по классу |
| Приписок в отчётах | **не найдено** | все заявленные артефакты существуют |

**Главный вывод:** реализация соответствует тому, что заявлено в отчётах. Приписок нет.
**НО** аудит вскрыл **6 новых дефектов паритета**, два из которых критические.

**⚠️ ДОПОЛНЕНИЕ 2026-09-05 (вечер): при разведке под A-3 найдена НОВАЯ находка A-7, более
серьёзная, чем A-3 и A-4 вместе.** В проде **невозможно задеплоить DMN-решение** — нет ни одного
пути загрузки (ни REST-эндпоинта, ни multipart, ни хука при деплое BPMN, ни клиентского SDK, ни
фронтенда); `DmnService.deploy` вызывается **только из тестов**. Значит весь DMN-функционал —
включая literal expressions (WO-C8-6) и decision requirements graph (WO-C8-10) — корректен по
коду, но **недостижим в реальной инсталляции**. Детали — §3, A-7. Выдан **WO-C8-15 (P0)**.

**ИТОГ 2026-09-05: ВСЕ СОДЕРЖАТЕЛЬНЫЕ НАХОДКИ АУДИТА ЗАКРЫТЫ.**

| Находка | Статус | SHA |
|---|---|---|
| A-1 `priorityDefinition` на неверном элементе | ✅ закрыта | `2e231723` |
| **A-5 `taskHeaders`/`executionListeners` уже схемы** | 🟡 **ЗАКРЫТА НАПОЛОВИНУ** | `taskHeaders` — закрыт (WO-C8-7 раунд 2, `39bf8ecf`: ScriptTask/SendTask не доставляли + вложенные у ExecutionListener). `executionListeners` шире SERVICE_TASK — **открыт** (WO-C8-11 раунд 2, часть B; дизайн готов). План числил A-5 за «C8-18» — номер занят деплоймент-агрегатом |
| **A-6 `ConditionalFilter`/`AdHoc`/`LinkedResource`** | 🔴 **ОТКРЫТА** | — план числил её за «C8-20», но этот номер ушёл под DMN versionTag; внесена в индекс 2026-09-05 |
| A-2 subprocess терял 12 типов из 21 | ✅ закрыта | `836fb2ec` + `a8b0379b` |
| A-3 пиннинг версии DMN | ✅ закрыта | `db0c0a86` |
| A-4 job на end/throw событиях | ✅ закрыта | `897b81f0` (через HOLD) |
| A-7 DMN невозможно задеплоить в проде | ✅ закрыта | `217bf68e` |
| A-5, A-6 | информационные, сознательно не берутся | — |

Каждая закрыта с независимой перепроверкой CTO (пересборка байт-в-байт, `test:pg` где требовался,
построчное чтение диффов). Один HOLD (A-4) предотвратил тихую регрессию по
signal/link/escalation/compensation throws.

**Предыдущий статус (сохранён для истории): обе ИСХОДНЫЕ критические находки ЗАКРЫТЫ.**
A-1 — WO-C8-13 (`2e231723`). A-2 — WO-C8-14 (`836fb2ec`) + WO-C8-14b (`a8b0379b`); проверено
машинно: внутри subprocess теперь **21 тип из 21**, потеряно ничего. Осталось: A-3, A-4 (P1) и
A-5, A-6 (информационные).

**Критические находки (детали — §3):**

- **A-1.** WO-C8-9 (`priorityDefinition`) реализован на **неверном имени элемента**. По
  официальной схеме `zeebe:priorityDefinition` разрешён **только на user task**, а для service
  task приоритет задаётся элементом `zeebe:jobPriorityDefinition`. Мы парсим `priorityDefinition`
  внутри ветки service task. **Реальная диаграмма из Camunda Modeler никогда не совпадёт с тем,
  что мы читаем.** Практический паритет от WO-C8-9 = 0.
- **A-2.** **Embedded subprocess поддерживает только 9 типов элементов из 21 — остальные 12 молча
  теряются на разборе XML** (`intermediateCatchEvent`, `intermediateThrowEvent`,
  `businessRuleTask`, `sendTask`, `receiveTask`, `callActivity`, вложенный `subProcess`,
  `transaction`, `inclusiveGateway`, `eventBasedGateway`, `boundaryEvent`, `association`).
  Сабпроцесс с таймером, с DMN-решением, с вложенным call activity, с boundary-таймаутом на
  внутренней задаче — деплоится **без единой ошибки** и ломается в рантайме инцидентом
  «target not found». Отправная точка — находка N1 Фазы 0, которая **не попала ни в один WO**;
  при проверке дефект оказался в разы шире, чем N1 описывала. По совокупности «частота паттерна ×
  тишина отказа» это, вероятно, **самый серьёзный дефект паритета во всём движке**.

---

## 1. Методология аудита

Аудит намеренно **не опирается на отчёты исполнителя**. Источники истины:

1. **Git** — `git log master --merges`, существование каждого SHA.
2. **Диск** — существование каждого заявленного файла и класса на текущем master.
3. **Трассировка потребления** — для каждой конструкции проверено не «класс создан», а
   «значение доходит до точки, где меняет поведение» (БД, wire-формат воркера, ветвление
   исполнения). Это принципиально: «распарсили и выбросили» = нулевой паритет.
4. **Официальная схема Camunda 8** — `zeebe-bpmn-moddle/resources/zeebe.json`, скачана
   **напрямую через curl** и разобрана локально. Промежуточный пересказ через LLM был отброшен
   как ненадёжный: он умолчал о `bindingType`/`versionTag`, которые в файле есть (объявлены в
   абстрактном миксине `BindingTypeSupported`). Все утверждения ниже — из сырого JSON.
5. **Официальная документация Camunda 8** — для семантики (порядок исполнения listener'ов,
   биндинг результата DMN и т. п.).

Ключевое правило схемы, определяющее имена XML-тегов: `"xml": {"tagAlias": "lowerCase"}` —
имя тега = имя типа с первой строчной буквой. `JobPriorityDefinition` → `zeebe:jobPriorityDefinition`.

---

## 2. Что сделано: 11 WO, статус по факту

Все проверены индивидуально. «Замкнуто» = значение доходит до точки, где меняет поведение.

| WO | SHA | Конструкция | Цепочка потребления (проверено) | Вердикт |
|---|---|---|---|---|
| C8-1 | `aa55d3f1` | Фаза 0: характеризация | 44 теста, прод не тронут | ✅ Чисто |
| C8-2 | `f5014792` | FEEL в `processId`/`decisionId` | `CallActivityHandler:67`, `SyncTaskHandler:146` → `resolveExpression` | ✅ Замкнуто |
| C8-3 | `da96fa7e` | `bindingType="versionTag"` | `CallActivityHandler:81` → `findMaxByKeyAndVersionTag` → БД (миграция 083) | ✅ Замкнуто |
| C8-4 | `6cb88dca` | Приоритет specific над catch-all | `ErrorEscalationThrower` — 3 места (105, 136, 244) | ✅ Замкнуто |
| C8-5 | `6da7c8c5` | Manual Task | `BpmnElementType.MANUAL_TASK` + handler + парсинг в 2 местах | ✅ Замкнуто |
| C8-6 | `7c296de3` | DMN literal expression | `DmnServiceImpl:105-107` → реальное вычисление | ✅ Замкнуто |
| C8-7 | `46c192ee` | `zeebe:taskHeaders` | `ServiceTaskEnqueueServiceImpl:112` → `JobDetailModel.taskHeaders` → воркер | ✅ Замкнуто |
| C8-8 | `9e049de2` | `zeebe:taskSchedule` | handler → `createUserTask` → `UserTaskEntity` (084) → mapper → DTO | ✅ Замкнуто |
| C8-9 | `8c38fd05` | `zeebe:priorityDefinition` | `ElementSupport:116` → `JobDetailModel.priority` | ⚠️ **Замкнуто технически, но на неверном элементе — см. A-1** |
| C8-10 | `065ca31d` | DMN DRG | `DmnServiceImpl:88/101/158` — рекурсия + cycle-guard | ✅ Замкнуто |
| C8-11 | `23cfc863` | `executionListeners` start-only | `pendingListenerIndex` через 7 файлов (085) | ✅ Замкнуто |

**Оценка качества исполнения:** высокая. Технических приписок нет; каждая заявленная цепочка
существует. Проблема WO-C8-9 — **не в исполнении, а в постановке**: WO был написан по фикстуре
Фазы 0, а фикстура содержала невалидное размещение элемента. Исполнитель реализовал ТЗ дословно
и правильно. Ошибка моя как автора ТЗ.

---

## 3. Новые находки аудита

### A-1 — ✅ ЗАКРЫТА (WO-C8-13, `2e231723`). Была: `priorityDefinition` на неверном элементе; паритет = 0

**Факт из схемы (сырой JSON):**

```
PriorityDefinition      allowedIn = ["bpmn:UserTask"]                      props=[priority]
JobPriorityDefinition   allowedIn = ["bpmn:Process", "zeebe:ZeebeServiceTask"]  props=[priority]
```

**Что делаем мы:** `BpmnParseServiceImpl:631` читает `getPriorityDefinition()` **внутри ветки
разбора service task**.

**Что это значит:**

| Реальный сценарий из Camunda Modeler | Что сгенерирует Modeler | Прочитаем ли мы |
|---|---|---|
| Приоритет job'а на service task | `<zeebe:jobPriorityDefinition priority="75"/>` | ❌ Нет — `jobPriorityDefinition` не парсится нигде (0 вхождений в коде) |
| Приоритет user task | `<zeebe:priorityDefinition priority="75"/>` на `<bpmn:userTask>` | ❌ Нет — читаем только в ветке service task |
| Приоритет job'а по умолчанию на процессе | `<zeebe:jobPriorityDefinition>` на `<bpmn:process>` | ❌ Нет |

**Вывод:** ни один реальный кейс не покрыт. Фикстура `test-c8-priority.bpmn` (Фаза 0) содержит
`<zeebe:priorityDefinition>` на `<bpmn:serviceTask>` — размещение, невалидное по схеме. WO-C8-9
корректно реализовал невалидную фикстуру.

**Стоимость исправления:** низкая (≈ как сам WO-C8-9). Инфраструктура резолва
(`ElementSupport.resolvePriority`, поле `JobDetailModel.priority`) уже есть и корректна —
менять нужно только имя элемента и точки чтения.

---

### A-2 — ✅ ЗАКРЫТА ПОЛНОСТЬЮ (WO-C8-14 `836fb2ec` + WO-C8-14b `a8b0379b`). Была: embedded subprocess поддерживал 9 типов из 21; остальные 12 молча терялись

**Как обнаружено:** отправной точкой был тест `intermediateThrowInsideSubprocess_isDroppedByParser`
(строка 1083) — валидная модель деплоится чисто и ломается в рантайме инцидентом
`escThrow ... not found`. Комментарий в самом тесте: «В gap-анализе этого нет» — это находка N1
Фазы 0, которая **не получила собственного WO** и потерялась при переходе к категории 2.

**При проверке выяснилось, что дефект шире.** Причина не в логике `toSubProcessElement`, а в самой
JAXB-модели: `BpmnSubProcessModel` объявляет только **9** типов детей, поэтому всё остальное
внутри `<bpmn:subProcess>` не доходит даже до парсера — теряется на разборе XML.

| Внутри subprocess поддержано (9) | **Молча теряется (12)** |
|---|---|
| `startEvent`, `endEvent`, `sequenceFlow`, `serviceTask`, `scriptTask`, `userTask`, `manualTask`, `exclusiveGateway`, `parallelGateway` | `intermediateCatchEvent`, `intermediateThrowEvent`, `businessRuleTask`, `sendTask`, `receiveTask`, `callActivity`, `subProcess`, `transaction`, `inclusiveGateway`, `eventBasedGateway`, `boundaryEvent`, `association` |

**Что это означает на практике.** Любой из этих совершенно обычных паттернов внутри сабпроцесса
разваливается в рантайме:

- сабпроцесс с **таймерным ожиданием** (`intermediateCatchEvent` с timer) — элемент исчезает;
- сабпроцесс с **ожиданием сообщения** — исчезает;
- сабпроцесс с **DMN-решением** (`businessRuleTask`) — исчезает;
- сабпроцесс с **вложенным call activity** — исчезает;
- **вложенный сабпроцесс** — исчезает целиком со всем содержимым;
- **boundary-событие на задаче внутри** сабпроцесса (таймаут на шаг) — исчезает;
- `inclusiveGateway`/`eventBasedGateway` внутри — исчезают.

Во всех случаях: деплой проходит **без единой ошибки**, поток доходит до пропавшего элемента и
упирается в инцидент «target not found». Это худший класс дефекта — тихое расхождение с
рантайм-поломкой валидной модели.

**Оценка серьёзности:** по совокупности «частота паттерна × тишина отказа» это, вероятно,
**самый серьёзный дефект паритета во всём движке** — серьёзнее любой отдельной незакрытой
`zeebe:`-конструкции, потому что бьёт по обычному моделированию, а не по продвинутым
расширениям.

---

### A-7 — ✅ ЗАКРЫТА (WO-C8-15, `217bf68e`). Была: в проде невозможно задеплоить DMN-решение

**Проверено исчерпывающе, каждая строка — отдельной командой:**

| Возможный путь загрузки DMN | Результат |
|---|---|
| REST-эндпоинт деплоя | ❌ `DmnContract` = ровно 3 метода: `GET /dmn`, `GET /dmn/{id}`, `POST /dmn/{id}/evaluate` |
| Multipart/файловая загрузка в `zorrobpm-rest` | ❌ 0 вхождений `MultipartFile`/`@RequestPart` во всём модуле |
| Хук при деплое BPMN | ❌ `ProcessDefinitionServiceImpl` — 0 упоминаний DMN |
| Композитная подача | ❌ `ProcessSubmissionServiceImpl` — 0 упоминаний DMN |
| Клиентский SDK | ❌ `zorrobpm-client` — 0 упоминаний DMN |
| Фронтенд | ❌ `DmnList.vue`/`DmnViewer.vue` — только просмотр |
| Кто вызывает `DmnService.deploy` | **только тесты** (6 тестовых классов) |

**Следствие:** любой процесс с `businessRuleTask` в реальной инсталляции упадёт с
`No deployed DMN decision '<id>'`. Весь DMN-модуль — decision tables, все hit policies, literal
expressions (WO-C8-6), decision requirements graph (WO-C8-10) — **код корректный, но
недостижимый в проде**.

**Это ретроспективно корректирует §4.3 настоящего аудита**, где DMN был отмечен как «паритет
достигнут». По внутренней реализации — да, и это подтверждено. По практической доступности —
нет: у нас есть движок решений и нет способа загрузить в него решение.

**Почему аудит этого не увидел с первого захода:** проверялась цепочка «конструкция → потребление
в движке», и она замкнута корректно. Не проверялась цепочка «а как артефакт вообще попадает в
систему». Урок для методологии: **паритет — это не только исполнение конструкции, но и путь её
доставки**.

→ **WO-C8-15 — ✅ смёржен (`217bf68e`)**: добавлен `POST /dmn` с авторизацией, побайтово совпадающей с BPMN-деплоем, и сквозным тестом через HTTP. **WO-C8-17 (A-3) этим разблокирован** — пиннинг версий того, что
нельзя задеплоить, не имеет смысла и не тестируется сквозным сценарием.

### A-3 — ✅ ЗАКРЫТА (WO-C8-17, `db0c0a86`). Была: `calledDecision` bindingType игнорируется (пиннинг версии DMN)

**Факт:** тест `calledDecisionBindingType_isIgnored_latestDecisionWins` (строка 716) явно
доказывает: decision задеплоена v1, вызывающий процесс задеплоен, затем прилетела v2 —
исполняется **v2**, хотя `bindingType="deployment"` требует остаться на v1.

**Схема:** `CalledDecision` наследует `BindingTypeSupported` → поддерживает `bindingType` и
`versionTag` ровно как `CalledElement`.

**Связь с A-1 по природе:** тихое расхождение поведения. Модель работает, но принимает решения
по другой версии правил, чем в Camunda 8. Для DMN (кредитные лимиты, тарифы, скидки) это прямой
бизнес-риск.

**Отдельно:** `FormDefinition` тоже наследует `BindingTypeSupported` — та же дыра для форм.

---

### A-4 — ✅ ЗАКРЫТА (WO-C8-16, `897b81f0`). Была: job worker не поддержан на end event / intermediate throw event

**Схема:** абстрактный тип `ZeebeServiceTask` (носитель `taskDefinition`, `taskHeaders`,
`jobPriorityDefinition`) расширяет:

```
bpmn:ServiceTask, bpmn:BusinessRuleTask, bpmn:ScriptTask, bpmn:SendTask,
bpmn:EndEvent, bpmn:IntermediateThrowEvent, bpmn:AdHocSubProcess
```

**Что у нас:** `getTaskDefinition` в методах разбора end event / intermediate throw event — **0
вхождений**. То есть message end event и intermediate message throw event, реализованные через
job worker (штатный паттерн Camunda 8 «отправить сообщение наружу»), у нас не работают как job.

---

### A-5 — СРЕДНЯЯ. `taskHeaders` и `executionListeners` реализованы уже, чем разрешает схема

| Конструкция | Схема разрешает | Мы реализовали |
|---|---|---|
| `TaskHeaders` | `bpmn:UserTask`, `zeebe:ExecutionListener`, `zeebe:ZeebeServiceTask` (7 типов) | только service task |
| `ExecutionListeners` | `Event`, `Activity`, `Process`, все 4 типа gateway | только service task, только `eventType="start"` |
| `TaskListeners` | `bpmn:UserTask` | не реализовано |

Это **осознанные сужения** (зафиксированы в границах WO-C8-7/C8-11), не дефекты исполнения. Но в
терминах «мы = Camunda 8» это незакрытая часть поверхности, и её надо честно держать в плане.

---

### A-6 — НИЗКАЯ. Не поддержаны 4 расширения из схемы

| Расширение | Где разрешено | Назначение | Оценка |
|---|---|---|---|
| `AdHoc` | `AdHocSubProcess` | ad-hoc сабпроцесс (8.7+) | Самый дорогой пункт всего эпика |
| `AgentDefinition` | `ServiceTask`, `AdHocSubProcess` | AI-агент коннектор (8.8+) | Нишевое, очень новое |
| `LinkedResource(s)` | `ServiceTask` | привязка RPA-ресурсов (8.7+) | Нишевое, вне нашего стека |
| `ConditionalFilter` | `ConditionalEventDefinition` | фильтр по `variableNames`/`variableEvents` для conditional-событий | Влияет на частоту переоценки условий |

---

## 4. Полная матрица паритета

### 4.1 Расширения `zeebe:` — все 26 типов официальной схемы

Легенда: ✅ полностью · 🟡 частично · ⏳ в работе · ⬜ нет · ⛔ сознательно не берём

| # | Тип (схема) | XML-тег | Разрешён на | Наш статус | Комментарий |
|---|---|---|---|---|---|
| 1 | `TaskDefinition` | `zeebe:taskDefinition` | ZeebeServiceTask (7 типов) | 🟡 | service/script/send task — да; end event, intermediate throw — **нет (A-4)** |
| 2 | `TaskHeaders` + `Header` | `zeebe:taskHeaders` | UserTask, ExecutionListener, ZeebeServiceTask | 🟡 | только service task **(A-5)** |
| 3 | `JobPriorityDefinition` | `zeebe:jobPriorityDefinition` | Process, ZeebeServiceTask | ⬜ | **не парсится вообще (A-1)** |
| 4 | `PriorityDefinition` | `zeebe:priorityDefinition` | **UserTask** | ⬜ | читаем в неверной ветке **(A-1)** |
| 5 | `AssignmentDefinition` | `zeebe:assignmentDefinition` | UserTask | ✅ | assignee, candidateUsers, candidateGroups |
| 6 | `UserTask` | `zeebe:userTask` | UserTask | ✅ | маркер Zeebe-native user task |
| 7 | `TaskSchedule` | `zeebe:taskSchedule` | UserTask | ✅ | WO-C8-8, dueDate/followUpDate |
| 8 | `TaskListeners` + `TaskListener` | `zeebe:taskListeners` | UserTask | ⬜ | не реализовано |
| 9 | `ExecutionListeners` + `ExecutionListener` | `zeebe:executionListeners` | Event, Activity, Process, 4× Gateway | 🟡 | WO-C8-11: service task + только `start` |
| 10 | `FormDefinition` | `zeebe:formDefinition` | UserTask, StartEvent | 🟡 | `formKey`, `externalReference` — да; `formId` — нет; `bindingType`/`versionTag` — нет |
| 11 | `UserTaskForm` | `zeebe:userTaskForm` | Process | ⏳ | **WO-C8-12 в работе** |
| 12 | `CalledElement` | `zeebe:calledElement` | CallActivity | 🟡 | `processId` (+FEEL), `versionTag`, propagate-флаги — да; `bindingType="deployment"` — нет; `processIdExpression`, `businessId` — нет |
| 13 | `CalledDecision` | `zeebe:calledDecision` | BusinessRuleTask | 🟡 | `decisionId` (+FEEL), `resultVariable` — да; `bindingType`/`versionTag` — **нет (A-3)** |
| 14 | `BindingTypeSupported` (миксин) | — | CalledElement, CalledDecision, FormDefinition, LinkedResource | 🟡 | только `versionTag` на call activity |
| 15 | `IoMapping` + `Input`/`Output` | `zeebe:ioMapping` | CallActivity, Event, ReceiveTask, ZeebeServiceTask, SubProcess, UserTask | ✅ | |
| 16 | `LoopCharacteristics` | `zeebe:loopCharacteristics` | MultiInstanceLoopCharacteristics | ✅ | inputCollection, inputElement, outputCollection, outputElement |
| 17 | `Script` | `zeebe:script` | ScriptTask | ✅ | FEEL-скрипты |
| 18 | `VersionTag` | `zeebe:versionTag` | Process | ✅ | WO-C8-3, хранится на каждой версии |
| 19 | `Subscription` | `zeebe:subscription` | MessageEventDefinition | ✅ | correlationKey |
| 20 | `ConditionalFilter` | `zeebe:conditionalFilter` | ConditionalEventDefinition | ⬜ | **A-6** |
| 21 | `LinkedResource` | `zeebe:linkedResource` | ServiceTask | ⛔ | RPA-интеграция, вне стека |
| 22 | `LinkedResources` | `zeebe:linkedResources` | ServiceTask | ⛔ | то же |
| 23 | `AdHoc` | `zeebe:adHoc` | AdHocSubProcess | ⬜ | самый дорогой пункт |
| 24 | `AgentDefinition` | `zeebe:agentDefinition` | ServiceTask, AdHocSubProcess | ⛔ | AI-коннектор, ниша |
| 25 | `ZeebeServiceTask` (миксин) | — | 7 типов элементов | 🟡 | см. A-4 |
| 26 | `Element` (база) | — | — | — | служебный |

**Сводка:** ✅ 8 · 🟡 8 · ⏳ 1 · ⬜ 5 · ⛔ 3 (+1 служебный).

### 4.2 BPMN-элементы: наши 45 типов против Camunda 8

| Группа | Camunda 8 исполняет | У нас | Разрыв |
|---|---|---|---|
| **Задачи** | service, user, script, business rule, send, receive, manual, undefined | все 7 + manual | ✅ паритет |
| **Шлюзы** | exclusive, parallel, inclusive, event-based (complex — **не исполняет и Zeebe**) | все 4 | ✅ паритет |
| **Start-события** | none, message, timer, signal, error*, escalation* (*в event subprocess) | none, message, timer, signal | 🟡 error/escalation start в event subprocess — есть через `EVENT_SUB_PROCESS` |
| **Intermediate catch** | message, timer, signal, link, conditional | все 5 | ✅ паритет |
| **Intermediate throw** | none, message, signal, link, escalation, compensation | все 6 | 🟡 **дропаются внутри subprocess (A-2)**; job-based throw — нет (A-4) |
| **End-события** | none, message, error, terminate, signal, escalation, compensation | все 7 | 🟡 job-based message end — нет (A-4) |
| **Boundary** | message, timer, signal, error, escalation, conditional, compensation, cancel | все 8 | ✅ паритет |
| **Подпроцессы** | embedded, event subprocess, call activity, transaction, ad-hoc (8.7+) | embedded, event sub, call activity, transaction | 🟡 ad-hoc — нет |
| **Multi-instance** | parallel + sequential, completionCondition | есть | ✅ паритет |
| **Компенсация** | boundary + throw + handler | есть | ✅ паритет |
| **Пулы/дорожки/data objects** | визуальные, на исполнение не влияют | игнорируем | ✅ паритет (поведение совпадает) |

### 4.3 DMN

| Возможность | Camunda 8 | У нас | Комментарий |
|---|---|---|---|
| Decision table | ✅ | ✅ | UNIQUE, FIRST, ANY, COLLECT, RULE ORDER, OUTPUT ORDER, PRIORITY |
| Literal expression | ✅ | ✅ | WO-C8-6 |
| Decision requirements graph | ✅ | ✅ | WO-C8-10, рекурсия + защита от циклов |
| Биндинг результата под `decisionId` | ✅ | ✅ | WO-C8-10 (исправлена и ошибочная фикстура) |
| Версионирование decision | ✅ | 🟡 | latest — да, `bindingType`/`versionTag` — **нет (A-3)** |
| FEEL-движок | feel-scala | **та же feel-scala** | ✅ идентичная реализация |

---

## 5. План: что берём, что не берём — с обоснованием каждого пункта

Критерий приоритизации — **не полнота спеки, а вероятность, что реальная диаграмма из Camunda
Modeler сломается или сработает иначе**. Второй критерий — риск изменения для движка.

### P0 — Брать немедленно (дефекты, найденные аудитом)

| WO | Что | Обоснование «почему берём» | Цена | Риск |
|---|---|---|---|---|
| **C8-13** | Исправить A-1: `jobPriorityDefinition` на service task + `priorityDefinition` на user task | WO-C8-9 сейчас не даёт паритета вообще. Это не «улучшение», а **закрытие ложно закрытого пункта**. Оставить как есть — держать в реестре неверную запись «сделано», что хуже, чем не делать | Низкая — инфраструктура резолва уже есть, меняются имя элемента и точки чтения | Низкий — только парсинг + существующее поле |
| **C8-14** | Исправить A-2, срез 1: 7 плоских flow-нод внутри сабпроцесса (`intermediateCatchEvent`, `intermediateThrowEvent`, `businessRuleTask`, `sendTask`, `receiveTask`, `inclusiveGateway`, `eventBasedGateway`) | Самый серьёзный дефект паритета: **валидная модель деплоится и ломается в рантайме**, и бьёт по обычному моделированию (таймер/DMN/ожидание внутри сабпроцесса), а не по редким расширениям | Средняя — механическое зеркалирование верхнеуровневого разбора в `BpmnSubProcessModel` + `toSubProcessElement` | Средний — парсер сабпроцессов, нужен широкий регресс |
| **C8-14b** | Исправить A-2, срез 2: `callActivity`, вложенный `subProcess`, `transaction`, `boundaryEvent`, `association` внутри сабпроцесса | Та же природа дефекта, но другой класс изменения — рекурсия `toSubProcessElement` и разбор `attachedToRef`. Вложенный сабпроцесс и boundary-таймаут на внутренней задаче — частые паттерны | Выше средней | Выше среднего — рекурсия + привязка boundary |

**Почему именно эти первыми:** оба — тихие расхождения поведения (категория 1 по определению
эпика), оба обнаружены только сейчас, оба дешевле, чем любой оставшийся пункт категории 2.

### P1 — Брать следующими (дёшево + реальная польза)

| WO | Что | Обоснование | Цена | Риск |
|---|---|---|---|---|
| **C8-12** | `zeebe:userTaskForm` (embedded form) | **Уже в работе.** Встраивание формы в BPMN — штатный workflow модельера, частый в реальных файлах. Переиспользует существующую таблицу форм | Низкая | Низкий — только deploy-time |
| **C8-15** | A-3: `bindingType`/`versionTag` для `calledDecision` | Тихое расхождение: решения принимаются по другой версии правил. Для DMN (тарифы, лимиты, скидки) — прямой бизнес-риск. Механизм резолва уже отработан на call activity (WO-C8-3) — копируем паттерн | Средняя | Низкий — deploy/resolve-time, не трогает completion-путь |
| **C8-16** | A-4: `taskDefinition` на end event / intermediate throw event | «Отправить сообщение наружу через воркера» — штатный паттерн Camunda 8. Сейчас такой элемент молча не станет job'ом | Средняя | Средний — новые точки диспетчеризации job'ов |

### P2 — Брать позже (дорого либо рискованно, но нужно для полноты)

| WO | Что | Обоснование | Почему не сейчас |
|---|---|---|---|
| **C8-11b** | `executionListeners` `eventType="end"` | Симметрия к уже сделанному `start` | **Дизайн-блокер:** guard в `CompletionService.completeServiceTask` отклоняет повторное завершение уже-COMPLETED активности, а end-listener вставляется именно после `completeActivity`. Приём с `pendingListenerIndex` не переносится. Нужно решение: отложить `completeActivity` до конца listener'ов **или** отдельный ID для listener-job'а. Требует дизайн-захода до ТЗ |
| **C8-17** | `taskListeners` на user task | Полнота поверхности listener'ов | Тот же класс риска — новый синхронный шаг в жизненном цикле user task |
| **C8-3b** | `bindingType="deployment"` для call activity | Тихое расхождение (берём более новую версию, чем должны) | **Дизайн-блокер:** `ElementArtifactBindingRepository`/`carryForwardBindings` жёстко завязан на `FormRepository`, не generic. Нужно решить: обобщать таблицу (риск для существующей form-логики) **или** заводить отдельную. Требует решения до ТЗ |
| ~~C8-18~~ → **WO-C8-7 р2 + WO-C8-11 р2** | `taskHeaders`/`executionListeners` на остальных разрешённых элементах (A-5). ⚠️ Номер «C8-18» из этого плана был позже занят деплоймент-агрегатом; реальные владельцы — раунды 2 у C8-7 (закрыт) и C8-11 (часть B открыта) | Полнота | Дешевле делать после того, как закрыты P0/P1; часть зависит от C8-16 |
| **C8-19** | `formId` + `bindingType` для `FormDefinition` | Внешние привязанные `.form`-ресурсы | Отдельный механизм (deploy-time версионируемый ресурс, ближе к DMN-резолву). Разведка нужна до ТЗ |
| ~~C8-20~~ → **без номера, в индексе строкой «A-6»** | `ConditionalFilter` (A-6). ⚠️ Номер «C8-20» был позже занят DMN versionTag; A-6 не начата | Влияет на частоту переоценки conditional-событий | Низкая частота в реальных моделях; сначала более частотное |

### P3 — Сознательно НЕ берём (с обоснованием)

| Пункт | Почему не берём |
|---|---|
| **`businessId`** на call activity | Апстрим Camunda сам ещё не закончил: это MVP версии **8.9**, части (позднее присвоение, кастомные выражения, корреляция сообщений) их же трекер явно откладывает на 8.10+. Реализовывать сейчас — строить на движущейся мишени и почти гарантированно переделывать. **Текущее молчаливое игнорирование — уже корректное поведение.** Пересмотреть, когда фича стабилизируется |
| **`processIdExpression`** | Реален в схеме, но вытеснен тем, что сам `processId` поддерживает `=FEEL` (закрыто WO-C8-2). Актуальный Camunda Modeler его не генерирует. Реализация тривиальна, но практическая ценность ≈ 0 — это работа ради строчки в отчёте |
| **`LinkedResource(s)`** | Привязка RPA-ресурсов Camunda. Вне нашего стека: нет RPA-раннера, которому это адресовано. Поддержка парсинга без исполнителя = бутафория |
| **`AgentDefinition`** | AI-агент коннектор, ≥8.8, очень новая и нишевая фича. Пересмотреть, если появится спрос |
| **Complex Gateway** | **Zeebe его тоже не исполняет.** Паритет уже достигнут по определению. ⚠️ Нюанс: Zeebe отклоняет деплой такой модели, мы — молча дропаем элемент и поток упирается в инцидент. Разница в *способе отказа*, не в возможностях. Дешёвая опция на будущее: валидировать на деплое и падать явно, как Zeebe |
| **`AdHoc` (ad-hoc subprocess)** | Не «не берём», а **берём последним осознанно**: динамическая активация элементов вне обычного sequence flow не ложится на текущий `FlowNavigator` без отдельного дизайна. Самый дорогой пункт эпика. Браться только после закрытия P0–P2 и отдельной архитектурной проработки |

---

## 6. Реестр рисков

| # | Риск | Вероятность | Влияние | Митигация |
|---|---|---|---|---|
| R-1 | Ещё одна фикстура Фазы 0 содержит неверное размещение/имя элемента → следующий WO снова закроет «не то» | **Средняя** (уже 3 подтверждённых случая: DMN DRG биндинг, `userTaskForm` имя+placement, `priorityDefinition` элемент) | Высокое | **Новое правило:** каждый WO обязан сверять свою фикстуру с сырой схемой `zeebe.json` и официальным примером **до** написания кода. Внести в шапку `cto-to-agent.md` |
| R-2 | Изменения в `CompletionService` ломают все процессы, а не только целевую конструкцию | Низкая | **Критическое** | Уже применяется: guard `isEmpty()` до чтения нового состояния, полный регресс + `test:pg` обязателен, ручная построчная проверка CTO |
| R-3 | Реестр «сделано» расходится с реальным паритетом (случай A-1) | Средняя | Высокое | Этот аудит + повторять сверку со схемой после каждых ~5 WO |
| R-4 | Дизайн-блокеры (C8-11b, C8-3b) откладываются бесконечно | Средняя | Среднее | Зафиксированы в эпик-файле с конкретной формулировкой развилки; берутся после P0/P1 отдельным дизайн-заходом |

---

## 7. Итог

**Достигнуто:** для подавляющего большинства реальных диаграмм ZorroBPM уже ведёт себя как
Camunda 8. Покрытие BPMN-элементов практически полное (45 типов, включая компенсацию, cancel,
terminate, escalation), FEEL-движок — буквально тот же (feel-scala), DMN закрыт вплоть до графа
зависимостей решений.

**Не достигнуто и требует работы:** 6 находок аудита, из них 2 критические (A-1, A-2), плюс
сознательно суженные области listener'ов и биндингов версий.

**Честная оценка позиционирования «та же Camunda 8, только бесплатная»:** заявление
выдерживает проверку по ядру исполнения, но **не выдержит придирчивого аудита по поверхности
расширений**, пока открыты A-1..A-4. После закрытия P0+P1 — выдержит по всем практически
значимым сценариям; P2 — вопрос полноты, а не работоспособности.

**Ближайшие 3 шага:** дождаться сдачи C8-12 → взять C8-13 (A-1, дёшево, закрывает ложную
запись в реестре) → взять C8-14 (A-2, единственный известный рантайм-ломающий дефект).
