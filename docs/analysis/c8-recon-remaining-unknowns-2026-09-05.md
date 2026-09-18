# Разведка: четыре оставшиеся неизвестные эпика (WO-C8-19)

Дата: 2026-09-05. Метод: сырая схема (`curl
https://raw.githubusercontent.com/camunda/zeebe-bpmn-moddle/main/resources/zeebe.json`,
локальный разбор python — файл проверен целиком, типов 36) + официальная дока Camunda 8
(docs.camunda.io, версия 8.9, с дословными цитатами). Память источником не являлась.
Итог: «не выяснено» — ноль пунктов; один подпункт Q1 помечен как проектное решение CTO,
а не пробел в фактах.

---

## Вопрос 1 — `zeebe:taskListeners`: легальные `eventType`

### Факты из сырой схемы (локальный разбор)

- `TaskListeners`: `allowedIn = ['bpmn:UserTask']` — только user task.
- `TaskListener`: `allowedIn = ['zeebe:TaskListeners']`, свойства — `eventType`, `retries`,
  `type`, все `String`, без enum (список значений — только из доки, как и предсказывал WO).

### Факты из официальной доки

Источник: https://docs.camunda.io/docs/components/concepts/user-task-listeners (концепты,
дословно; страница 8.9).

Легальные значения:

> `eventType` (Required) Specifies the user task event type that triggers the listener.
> Supported values are `creating`, `assigning`, `updating`, `completing`, and `canceling`.

Плюс значение `all` — только для глобальных (cluster-wide) листенеров, не для BPMN-атрибута.
Источник: спецификация `POST /v2/global-task-listeners`:
`List of user task event types that trigger the listener. Possible values:
[all, creating, assigning, updating, completing, canceling]`.

Что означает каждый (таблица «Trigger a user task listener», дословно):

| Событие | Триггер |
|---|---|
| `creating` | When a process instance reaches a user task. |
| `assigning` | When the assign user task API is called. / When activating a user task that specifies an `assignee` in the process. / When a user task is assigned using the Tasklist interface. |
| `updating` | When the update user task API is called. / When the update element instance variables API is called on a user task instance. / When the set variables RPC is called on a user task instance. / When variables are set at a user task scope using the Operate interface. |
| `completing` | When a user task is completed using the Tasklist interface. / When the complete user task API is called. |
| `canceling` | When a canceling process instance terminates a user task. / When a catch event interrupts a user task. |

Блокирует ли переход (ключевой архитектурный вопрос WO):

> User task listeners operate in a blocking manner, meaning the user task lifecycle transition
> is paused until the task listener completes. This ensures that any corrections or validations
> defined by the task listener are fully applied before the task transition continues.

Да — блокирует, как наши execution listeners в WO-C8-11b: переход ЖЦ ставится на паузу до
завершения job'а листенера. Архитектура будущего WO — та же (индекс в полёте + незавершённое
состояние), только якорь не service task, а user task.

Остальное из той же страницы (дословно, сжато):

- Исполнение — job workers, «similar to execution listeners and service task jobs»; после
  триггера «the workflow engine creates a job that you can process using a job worker».
- Несколько листенеров одного eventType на одной задаче: «they are executed sequentially, one
  after the other, in the order they are defined in the BPMN model» (та же семантика порядка,
  что execution listeners).
- `retries` — optional, «defaults to 3 if omitted»; свойства `type`/`retries` «are evaluated
  right before the job creation»; `type` — static или FEEL (`= "order-" + priorityGroup`).
- Коррекции данных (`correctAssignee`, `correctDueDate`, `correctFollowUpDate`,
  `correctCandidateUsers`, `correctCandidateGroups`, `correctPriority(80)` — пример из доки);
  применяются к задаче при финализации перехода, видны последующим листенерам.
  Внимание для нас: `correctPriority` — ТРЕТИЙ смысл слова priority в эпике (не
  `resolvePriority` из WO-C8-9 и не `correctPriority()` task-listener механизма 8.7+, о котором
  предупреждал WO-C8-9, — а результат job'а task listener'а). Не путать все три.
- Отмена перехода (`deny`): можно для `assigning`, `updating`, `completing`;
  «it's not possible to deny the creation or cancelation of a user task»; при deny коррекции
  отбрасываются, состояние сохраняется «as if the lifecycle event never occurred».
- Инциденты: падение job'а → retries → инцидент, переход на паузе до resolve; ошибка вычисления
  выражения → инцидент + ретрай ВСЕГО перехода включая уже успешные листенеры; падение job'а
  после resolve — ретраится только упавший.
- Ограничения (дословно): «No variable handling: User task listener jobs cannot be completed
  if variables are provided.» и «No BPMN error throwing: Throwing BPMN errors from user task
  listener jobs is not supported.»

Что реально исполняется сегодня: все пять событий документированы как поддерживаемые в 8.9;
по roadmap Camunda `assigning`/`completing` — релиз 8.8 (октябрь 2025, статус completed),
`creating` — релиз 8.8. Отдельного «превью»-маркера у фичи в доке 8.9 нет. Вывод: брать можно
все пять, нереализованного среди них нет (в отличие от прецедента с `businessId`).

### Что осталось неясным

Ничего по фактам. Проектное решение за CTO (не пробел в источниках): в каком порядке брать
события, если WO режут по фазам (кандидат: `creating`+`completing` первыми — создание и
завершение покрывают главный кейс валидации; `assigning`/`updating`/`canceling` — второй заход).

### Оценка стоимости для ZorroBPM

Якорные классы реальны (проверены в репозитории): парсинг — `ExtensionElements` +
`BpmnParseServiceImpl` (новый `TaskListenersModel`/`TaskListenerModel`, зеркало C8-11);
исполнение — пауза перехода user task (аналог end-фазы WO-C8-11b: индекс в полёте +
незавершённое состояние; точка входа — путь создания/завершения user task вокруг
`CompletionService.completeUserTask`); job — существующий outbox-механизм
(`JobDetailModel`, как C8-11); коррекции — сеттеры атрибутов задачи + `deny`-ветка;
инциденты — существующий механизм. Новое по существу: промежуточные состояния ЖЦ
(`CREATING`/`ASSIGNING`/… — сам Tasklist v1 их перечисляет как реальные состояния Zeebe)
и job-результат с коррекциями/deny (у нас `completeServiceTask` такого результата не несёт).
Оценка: средний WO уровня WO-C8-11b, без новых таблиц на первом заходе.

### Рекомендация: БРАТЬ

Стабильно (8.8+), задокументировано, все пять событий реальны, семантика блокировки прямо
ложится на проверенный паттерн WO-C8-11b. Предлагаю фазировать: создание+завершение, затем
остальные три.

---

## Вопрос 2 — `formDefinition`: `formId` / `externalReference` / `bindingType`

### Факты из сырой схемы (локальный разбор)

- `FormDefinition`: свойства `formKey`, `formId`, `externalReference` (все `String`);
  `allowedIn = ['bpmn:UserTask', 'bpmn:StartEvent']`.
- Миксин `BindingTypeSupported` (`bindingType`, default `'latest'`, + `versionTag`) —
  абстрактный тип с `extends: ["zeebe:CalledDecision", "zeebe:CalledElement",
  "zeebe:FormDefinition", "zeebe:LinkedResource"]`. То есть у форм **есть** `bindingType` и
  `versionTag` — утверждение WO подтверждено первоисточником, а не пересказом.

### Факты из официальной доки

Источник: https://docs.camunda.io/docs/components/modeler/bpmn/user-tasks/ (XML reference,
дословно). Три взаимоисключающие формы ссылки:

1. **Linked Camunda Form** — `formId` + опционально `bindingType`:
   «To link a user task to a Camunda Form, you have to specify the ID of the Camunda Form as
   the `formId` attribute of the task's `zeebe:formDefinition` extension element.»
   `bindingType`: «`latest`: The latest deployed version at the moment the user task is
   activated.», «`deployment`: The version that was deployed together with the currently
   running version of the process.», «`versionTag`: The latest deployed version that is
   annotated with the version tag specified in the `versionTag` attribute.» Дефолт — `latest`.
   Пример: `<zeebe:formDefinition formId="configure-control-process" bindingType="deployment" />`.
2. **Custom form reference** — `externalReference`: «A custom form reference can specify any
   custom identifier in the user task», «How the identifier is interpreted depends on your
   implementation», «will not be shown in Tasklist». Для job-worker задач тот же смысл несёт
   **`formKey`** вместо `externalReference`.
3. **Embedded** — `zeebe:userTaskForm` в процессе + ссылка через `formKey`
   (`camunda-forms:bpmn:…`) — наш WO-C8-12, только для job-worker задач.

Где живёт связанный `.form`-ресурс (вопрос WO): отдельный файл формы в проекте Web Modeler.
Источник: https://docs.camunda.io/docs/components/modeler/web-modeler/advanced-modeling/form-linking/
(дословно): «Using a linked form is the recommended approach… when deploying a BPMN diagram,
Web Modeler will always deploy the latest version of all linked forms along with the diagram».
Уточнение 8.9 (та же страница): «Linked Camunda Forms must be explicitly deployed» (кнопка
Deploy; раньше 8.4 — автодеплой). То есть `.form` — **отдельный версионируемый ресурс** со
своим деплоем, а не часть BPMN-файла; привязка — `formId` + binding.

Общая семантика биндингов — одна на процессы/решения/формы. Источник:
https://docs.camunda.io/docs/components/best-practices/modeling/choosing-the-resource-binding-type/
(дословно): «Camunda 8 offers version binding for linked processes, decisions, or forms»;
`deployment`: «Resolves to the specific version of the target resource that was **deployed
together** with the currently running version of the process in the **same deployment**»;
«To use the `deployment` binding option, create and deploy a process application in Web Modeler,
or deploy multiple resources together via the Zeebe API.»; `versionTag`: «You can set the
version tag for a BPMN process, DMN decision, or Form in the Modeler's properties panel»;
«If the target resource ID and version tag pair are not deployed, the process instance will
have an incident.»

Что генерирует Modeler сегодня: linked-форму через кнопку Link (пишет `formId` + binding),
embedded — вставку JSON, custom — `externalReference`/`formKey`.

### Что осталось неясным

Ничего по фактам. Проектное решение за CTO: порядок (formId+latest → deployment → versionTag).

### Оценка стоимости для ZorroBPM

Якоря реальны: `FormDefinitionModel` сегодня несёт только `formKey`/`externalReference`
(`formId`/`bindingType`/`versionTag` не парсятся); парсинг — `BpmnParseServiceImpl:1000-1005`
(`formKey` → `UserTaskExtensionModel.formKey`); резолв — `FormArtifactService` через
`FormRepository.findTopByFormKeyOrderByVersionDesc` (всегда latest, биндинга нет);
наша таблица `FormEntity` ключуется строкой `formKey`. Нужно: допарсить `formId`+`bindingType`
(+`versionTag` на потом), реестр `.form`-ресурсов (либо колонка `formId` рядом с `formKey`,
либо отдельная таблица — решение дизайна), резолв latest/deployment/versionTag при активации
задачи (deployment — естественный потребитель нашего `POST /deployments` из WO-C8-18:
пачка BPMN+FORM; versionTag — запрос latest-with-tag), плюс FORM как третий тип ресурса пачки.
Оценка: средний WO (сопоставим с C8-17), опора C8-18 уже есть — ответ на вопрос WO
утвердительный: отдельный реестр не обязателен, достаточно расширения существующего
(`FormEntity` + пачка), но `formId` как ключ адресации ввести придётся.

### Рекомендация: БРАТЬ

После taskListeners (вопрос 1) или параллельно другим исполнителем: стабильно,
задокументировано, Modeler генерирует `formId` по умолчанию (практическая частота высокая),
наша опора (`POST /deployments`, `FormEntity`) уже стоит. Фазировка: `formId`+latest →
`deployment` через пачку → `versionTag`.

---

## Вопрос 3 — `versionTag` для DMN: где живёт тег (мутная точка — РАЗРЕШЕНА)

### Факты

Факт из схемы (подтверждаю локальным разбором): тип `VersionTag` имеет
`meta.allowedIn = ['bpmn:Process']` — это тег **BPMN-процесса**, и только его.

Разрешение противоречия — тег DMN живёт **внутри DMN XML**, в самом решении. Источник:
camunda-modeler#4454 «Add support for setting a version tag for a DMN decision»
(https://github.com/camunda/camunda-modeler/issues/4454, статус closed/completed, релиз
dmn-js-properties-panel 3.5.0): «A new (optional) input field "Version tag" is added to the
"General" section of the properties panel for a decision in a DMN diagram» и «The version tag
is stored in the `value` attribute of a `zeebe:versionTag` extension element» — дословный пример
из issue:
```xml
<decision id="test-decision" name="Test Decision">
    <extensionElements>
        <zeebe:versionTag value="v1.0.0" />
    </extensionElements>
</decision>
```
Движковая сторона подтверждает модель хранения «тег на версии ресурса»: camunda/camunda#21036
«Store deployed decisions by id and version tag»
(https://github.com/camunda/camunda/issues/21036): «To support the new binding type versionTag
for business rule tasks, Zeebe needs to be able to resolve a called decision by decision id
and version tag.» Плюс дока (choosing-the-resource-binding-type, дословно): «You can set the
version tag for a BPMN process, DMN decision, or Form in the Modeler's properties panel» и
«If the target resource ID and version tag pair are not deployed, the process instance will
have an incident.»

Итого: противоречия нет — в схеме два разных факта о двух разных местах. `VersionTag/allowedIn
→ Process` описывает тег процесса; DMN-решение несёт свой собственный `zeebe:versionTag`
расширением внутри `<decision>`. Резолв `calledDecision bindingType="versionTag"` = latest
задеплоенная версия решения, аннотированная тегом; нет пары — инцидент.

### Что осталось неясным

Ничего, что блокирует WO: точное серверное хранение Zeebe (таблица/индекс) непублично, но для
дизайна достаточно модели «тег — атрибут версии ресурса» (та же, что мы уже реализовали для
процессов в WO-C8-3 через `versionTag`-колонку).

### Оценка стоимости для ZorroBPM

Якоря реальны: `DmnDecisionModel` сегодня НЕ читает `extensionElements` вообще (проверено
грепом — в `dmn/xml` пакете нет ни одного упоминания zeebe-неймспейса, это будет первый
кросс-неймспейс JAXB там); `DmnDefinitionEntity` несёт `(decisionId, version, dmn, deploymentId,
processDefinitionId)` — нужен nullable `version_tag` + миграция; `DmnDefinitionRepository` —
запрос `(decisionId, versionTag) → latest version` (образец: соседний pinned-запрос WO-C8-17);
`DmnServiceImpl.evaluate` — ветка в диспетче binding'ов рядом с `deployment` из WO-C8-17;
`SyncTaskHandler.BusinessRuleTask` — ничего нового (binding уже приходит в `ext`).
Оценка: маленький WO уровня WO-C8-17 (парсинг + колонка + запрос + ветка + тесты).

### Рекомендация: БРАТЬ

Мутная точка разрешена первоисточниками с двух сторон (Modeler + engine-issue + дока).
Прямое продолжение WO-C8-17, та же форма.

---

## Вопрос 4 — ad-hoc subprocess: как исполняется

### Факты из официальной доки

Источник: https://docs.camunda.io/docs/components/modeler/bpmn/ad-hoc-subprocesses/ (дословно;
стабильная страница 8.9, preview-маркеров нет — фича стабильна).

Что это: «Ad-hoc sub-processes are a special kind of embedded subprocess with an ad-hoc marker
(represented by a ~ tilde character).» Внутренние элементы «are not connected to a start or end
event. Each element can be executed multiple times, in any order, or skipped.» Ограничения:
«Must have at least one activity», «Must not have start events or end events».

Два режима исполнения:

1. **Внутренний (по умолчанию)**: «By default, ad-hoc sub-processes are handled internally in
Zeebe. You can model which elements to activate and when the sub-process is completed.»
   - Активация: «An ad-hoc sub-process can define an expression `activeElementsCollection`
     that should return a list of strings. Each string in the list should match to an ID of an
     inner element», вычисляется при входе; «If the list is empty or the expression is not
     defined, no element is activated and the ad-hoc sub-process remains active»; плохие
     значения — инцидент. «Currently, it is not possible to activate elements dynamically
     after the ad-hoc sub-process is activated, only on entering the subprocess.»
   - Завершение: опциональный `completionCondition` («evaluated every time an inner element is
     completed» → `true` = завершить и пойти дальше); без него — «completed after all activated
     elements are completed». `cancelRemainingInstances` (дефолт `true`): `true` — «all remaining
     active instances of inner elements are terminated», `false` — ждёт всех.
2. **Job worker**: «To do this, define the sub-process with a task definition. The job worker
   can then control the sub-process by activating inner elements and deciding when it completes»
   (создаёт job при активации; `adHocSubProcessElements`-переменная с метаданными
   `elementId/elementName/documentation/properties/parameters`; результат `adHocSubprocess` с
   `activateElements`; новый job на каждое завершение внутреннего flow; одновременно один
   активный job; возможны `NOT_FOUND`-реджекты; event subprocess внутри — вне прямого контроля
   воркера).

Выход: «`outputCollection` defines the variable name… created as a local variable of the ad-hoc
sub-process and updated whenever an inner flow completes… propagated to the parent scope» +
«`outputElement` defines the output of the inner flow (for example, `= result`)».

Маппинги: input — при активации до вычисления `activeElementsCollection`; output — при
завершении; «By default, no local variables are propagated.»

XML (из доки, дословно):
```xml
<bpmn:adHocSubProcess id="ad-hoc-subprocess" name="Ad-hoc sub-process" cancelRemainingInstances="false">
  <bpmn:extensionElements>
    <zeebe:adHoc activeElementsCollection="=activeElements" />
  </bpmn:extensionElements>
   ... more contained elements ...
  <bpmn:completionCondition xsi:type="bpmn:tFormalExpression">=myCondition</bpmn:completionCondition>
</bpmn:adHocSubProcess>
```
Схема подтверждает поля дословно (локальный разбор): `AdHoc.allowedIn = ['bpmn:AdHocSubProcess']`,
props `activeElementsCollection`/`outputCollection`/`outputElement`.

Связь с multi-instance (вопрос WO): дока **не** связывает ad-hoc с MI напрямую — это разные
механизмы (ad-hoc = свободный набор активаций + условие завершения; MI = N копий одного
элемента по коллекции). Пересечение одно: внутренние элементы ad-hoc могут сами быть MI
(дока этого не запрещает); отдельного «ad-hoc × MI»-механизма нет.

### Что осталось неясным

Ничего по фактам. Проектное решение за CTO: брать ли оба режима сразу или только внутренний.

### Оценка стоимости для ZorroBPM

Самая дорогая из четырёх — честно. Нужно: парсинг нового типа элемента
(`bpmn:adHocSubProcess` + `zeebe:adHoc` + `completionCondition` + `cancelRemainingInstances` —
сегодня такого типа в `BpmnElementType`/парсере нет, проверено отсутствием в scope прошлых WO);
НОВАЯ семантика исполнения (набор активных элементов вместо одного токена; вычисление
`activeElementsCollection` при входе (существующий `FeelEngineApi`); проверка условия
завершения после каждого внутреннего завершения; скоуп-переменная `outputCollection`;
отмена остатков при `cancelRemainingInstances=true`). Наша токенная модель
(`ActivityServiceImpl.execute`, счётчики ветвей из WO-ENG-12) рассчитана на детерминированный
flow — свободный набор параллельных активаций с динамическим условием выхода это расширяет
существенно. Job-worker вариант вторым заходом переиспользует outbox (`JobDetailModel`),
но добавляет протокол `adHocSubProcessElements`/`adHocSubprocess`-результата.
Оценка: эпик в эпике — минимум 2 WO (парсинг+внутреннее исполнение; job-worker вариант),
вероятно больше.

### Рекомендация: БРАТЬ, НО ПОСЛЕДНИМ И ПО ЧАСТЯМ

Стабильно (8.9, без preview), практически ценно — но на порядок дороже остальных трёх.
Предлагаю: сначала Q1→Q2→Q3 (каждый — один WO формы C8-11b/C8-17), ad-hoc — отдельным
мини-эпиком (1: парсинг + внутреннее исполнение + output; 2: job-worker вариант).

---

## Сводка для CTO (таблица решений)

| # | Вопрос | Вердикт | Основание |
|---|---|---|---|
| 1 | `taskListeners` eventTypes | БРАТЬ (фазировать: creating+completing → остальные) | 5 событий, все стабильны с 8.8, блокирующая семантика = проверенный паттерн C8-11b |
| 2 | `formId`/`externalReference`/binding | БРАТЬ (фазировать: formId+latest → deployment через пачку → versionTag) | Modeler генерирует formId по умолчанию; опора (`POST /deployments`, `FormEntity`) стоит |
| 3 | versionTag для DMN | БРАТЬ (прямо следующим за C8-17) | Противоречие разрешено: тег внутри `<decision>`, Modeler + engine-issue подтверждают |
| 4 | ad-hoc subprocess | БРАТЬ ПОСЛЕДНИМ, по частям | Стабилен, но самый дорогой (новая семантика исполнения) |

Пунктов «не выяснено»: 0 (один подпункт Q1 — порядок фаз — решение CTO, не пробел в фактах).
Граница WO соблюдена: ноль изменений в `src/` (только этот `.md`), фикстуры не тронуты,
парсинг `formId` не добавлен.
