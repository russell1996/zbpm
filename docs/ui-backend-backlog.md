# UI ↔ Backend backlog (ZorroBPM)

Анализ существующего фронтенда (`zorrobpm-frontend`, Vue 3 + Pinia + vue-router + axios) и
бэкенда (`zorrobpm-contract` / `zorrobpm-rest` / `zorrobpm-engine`). Цель — довести уже
реализованное до рабочего состояния **без редизайна**: подключить недостающие API, включить
серверную фильтрацию, убрать клиентскую фильтрацию больших наборов.

Дата: 2026-06-22.

---

## 1. Сводка состояния по разделам

| Раздел | Статус | Главная проблема |
|---|---|---|
| Processes (список/BPMN/деплой) | ✅ работает | версии процесса в detail не показываются |
| Processes — версии | ⚠️ частично | UI не выводит список версий (API есть: `?processDefinitionKey=`) |
| Process Instances (список) | ⚠️ частично | фильтр `processDefinitionId` шлётся, но **бэк его игнорирует** |
| Process Instance (detail) | ⚠️ частично | нет подсветки активных/завершённых/инцидентных элементов; нет истории; нет вызванных подпроцессов |
| User Tasks | ⚠️ частично | фильтр `completed` игнорируется; `GET /user-tasks/{id}` отсутствует |
| Service Tasks | ⚠️ частично | фильтр `completed`/`jobType` игнорируется; `GET /service-tasks/{id}` отсутствует |
| Incidents | ⚠️ частично | фильтры `processDefinition*` игнорируются; фильтр resolved/open — **клиентский**; `GET /incidents/{id}` отсутствует |
| Timers | ❌ мок | нет read-API (таблица `timer_jobs` есть) |
| Messages | ❌ мок | нет read-API (таблица `message_subscriptions` есть) |
| DMN | ❌ мок | нет read/evaluate-API (таблица `dmn_definitions` + `DmnService` есть) |
| Admin / Users | ❌ мок | бэкенда нет вовсе (см. план авторизации) |
| Analytics / Dashboard | ⚠️ частично | зависит от статистики/моков |

---

## 2. Корневая причина «фильтры не работают»

Query-DTO (`*Query`) и фронтовые типы содержат поля фильтров, но `QueryServiceImpl`
применяет лишь часть из них. Незадействованные поля молча игнорируются (запрос возвращает
более широкий набор, что выглядит как «фильтр не работает» или «инцидент не виден»).

`QueryServiceImpl` сейчас применяет:

- **ServiceTask**: `id`, `processInstanceId`. Игнор: `completed`, `jobType`.
- **UserTask**: `id`, `processInstanceId`. Игнор: `completed`, `assigned`, `assignee`, `candidateGroup`, `candidateUser`.
- **ProcessInstance**: `id`, `parentProcessInstanceId`. Игнор: `processDefinitionId`, `processDefinitionKey`, `processDefinitionVersion`.
- **Incident**: `id`, `processInstanceId`, `bpmnElementId`. Игнор: `processDefinitionId/Key/Version`; фильтра resolved/open нет вовсе.
- **Variables**: `processInstanceId`, `name`, `type`, `value` — ✅ полностью.
- **ProcessDefinitions**: `name`, `key`, `version`, `latestVersionOnly` — ✅ полностью (отдельный сервис).

### Ограничения данных (фильтры, требующие миграций — вне текущего объёма)
- `ServiceTask.jobType` — поле `job` **не хранится** в `service_tasks` (деривится из BPMN в маппере). Нужна колонка/деривация.
- `UserTask.candidateGroup` / `candidateUser` — колонок в `user_tasks` нет (есть только `assignee`).

---

## 3. Недостающие/частичные API

### 3.1 Single-entity GET (есть в сервисе, не выставлены в контракт)
`QueryService` уже содержит `getProcessInstance(id)`, `getUserTask(id)`, `getServiceTask(id)`,
`getIncident(id)`, но в `QueryContract`/`QueryResource` они **не объявлены**. Из-за этого
фронтовые стора грузят «список из 100 и ищут по id» (`fetchInstance`, `fetchUserTask`,
`fetchServiceTask`, `fetchIncident` — все с `TODO: Backend needs GET /{id}`).

| Метод | Endpoint | Request | Response |
|---|---|---|---|
| getProcessInstance | `GET /process-instances/{id}` | path id | `ProcessInstance` |
| getUserTask | `GET /user-tasks/{id}` | path id | `UserTask` |
| getServiceTask | `GET /service-tasks/{id}` | path id | `ServiceTask` |
| getIncident | `GET /incidents/{id}` | path id | `Incident` |

### 3.2 Активности экземпляра (история + подсветка BPMN)
`ProcessInstanceDetail.vue` передаёт в `BpmnViewer` пустые `active-element-ids`,
`incident-element-ids`, `completed-element-ids` — подсветки на диаграмме нет; вкладки
«история выполнения» нет. Нужен:

| Endpoint | Request | Response | Назначение |
|---|---|---|---|
| `GET /process-instances/{id}/activities` | path id | `List<ActivityInstance>` (id, bpmnElementId, type, status, createdAt, completedAt) | история + множества элементов CREATED/IN_PROGRESS/COMPLETED/ERROR для подсветки |

Модель `Activity` в контракте уже есть, но без `status` — для подсветки нужен `status`.

### 3.3 Вызванные подпроцессы (called subprocesses)
API есть: `GET /process-instances?parentProcessInstanceId={id}` (фильтр поддержан). UI-вкладки
нет — добавить в `ProcessInstanceDetail`.

### 3.4 Полностью мок-разделы (read-API отсутствует)
- **Timers**: `GET /timers` (таблица `timer_jobs` есть) — *бэклог, требует нового эндпоинта/репо-запроса*.
- **Messages**: `GET /message-subscriptions` (таблица есть) — *бэклог*.
- **DMN**: `GET /dmn`, `GET /dmn/{id}`, `POST /dmn/{id}/evaluate` (`DmnService` есть) — *бэклог*.
- **Admin/Users**: бэкенда нет — связано с планом авторизации (отдельная задача).

---

## 4. Нарушения «никакой клиентской фильтрации больших наборов»

- `IncidentList.vue` — `filteredIncidents` фильтрует resolved/open **на клиенте** (и только текущую страницу). → серверный фильтр `resolved`.
- Все сервисы (`processService`, `instanceService`, `taskService`, `incidentService`) применяют `desc()` — реверс массива **на клиенте** для сортировки по убыванию. → серверная сортировка (по `createdAt`/`startedAt` desc).
- `TaskList.vue` — `activeTasks` (для bulk/select-all) считается на клиенте; допустимо как UI-хелпер, но «показать завершённые» должно идти через серверный `completed`.

---

## 5. План работ (этот заход)

**Backend (этапы 2–3):**
1. Выставить single-entity GET в `QueryContract`/`QueryResource` (4 эндпоинта; сервис-методы уже есть).
2. Подключить недостающие фильтры в `QueryServiceImpl` + спецификации в репозиториях:
   - ProcessInstance: `processDefinitionId`, `processDefinitionKey`, `processDefinitionVersion`.
   - ServiceTask: `completed`.
   - UserTask: `completed`, `assigned`, `assignee`.
   - Incident: `processDefinitionId/Key/Version` + новый `resolved`.
3. `GET /process-instances/{id}/activities` (+ `ActivityInstance` DTO, маппер, репо-запрос).
4. Серверная сортировка по умолчанию (desc) в find*.

**Frontend (этап 4):**
5. Сервисы: добавить single-GET и `getProcessInstanceActivities`, `getProcessDefinitionVersions`; убрать `desc()`.
6. Стора: использовать single-GET вместо «список из 100».
7. `IncidentList`: серверный `resolved` вместо клиентского computed.
8. `ProcessInstanceDetail`: подсветка BPMN по активностям + вкладки «История» и «Подпроцессы».
9. `ProcessDefinitionDetail`: список версий.

**Бэклог (вне захода, требует отдельной работы/миграций):**
- `jobType`, `candidateGroup/User` фильтры (миграции схемы).
- Timers / Messages / DMN read-API.
- Admin/Users (план авторизации).

---

## 6. Реализовано в этом заходе

### Backend
- `QueryContract` / `QueryResource`: `GET /process-instances/{id}`, `/user-tasks/{id}`, `/service-tasks/{id}`, `/incidents/{id}`, `/process-instances/{id}/activities`; метод инцидентов переименован `getProcessInstances`→`getIncidents`.
- `QueryServiceImpl`: подключены фильтры — ProcessInstance `processDefinitionId/Key/Version`; ServiceTask `completed`; UserTask `completed/assigned/assignee`; Incident `processDefinitionId/Key/Version/resolved`. Добавлена серверная сортировка (createdAt/startedAt desc). Метод `getActivities`.
- Спецификации: `ServiceTaskRepository.byCompleted`; `UserTaskRepository.byCompleted/byAssigned/byAssignee`; `ProcessInstanceRepository.byProcessDefinitionKey/byProcessDefinitionVersion`; `IncidentRepository.byResolved/byProcessDefinitionId/Key/Version`; `ActivityRepository.findByProcessInstanceIdOrderByCreatedAtAsc`.
- Новое: `model/ActivityInstance` (DTO), `mapper/ActivityInstanceMapper`; поле `IncidentQuery.resolved`.

### Frontend
- Сервисы: `getProcessInstance/getUserTask/getServiceTask/getIncident` (single-GET), `getProcessInstanceActivities`, `getProcessDefinitionVersions`; убран клиентский реверс `desc()` (сортировка на сервере).
- Стора: `process` (single-GET инстанса, `fetchActivities`, `fetchVersions`, состояния `currentActivities/currentVersions`), `task` и `incident` — single-GET вместо «список из 100»; `resolveIncident` без дублирующего `id`.
- `IncidentList`: серверный фильтр `resolved` вместо клиентского `computed`.
- `ProcessInstanceDetail`: подсветка BPMN (active/incident/completed из активностей) + вкладка «История».
- `ProcessDefinitionDetail`: список версий процесса.
- Типы `ActivityInstance`, `IncidentQuery.resolved`; локали `history/noHistory/versions` (ru/en/kz).

### Проверка
- Полный backend-реактор: BUILD SUCCESS (10/10 модулей; engine 135 unit + 78 integration, rest 17).
- Frontend: `tsc && vite build` — успешно.

### Осталось в бэклоге (требует отдельной работы / миграций)
- Фильтры `ServiceTask.jobType`, `UserTask.candidateGroup/candidateUser` — нет колонок (миграция/деривация).
- Read-API для Timers / Messages / DMN (таблицы/сервисы есть, эндпоинтов нет) — разделы на моках.
- Admin/Users — бэкенда нет (см. план авторизации).
- Вкладка «вызванные подпроцессы» в ProcessInstanceDetail (API `?parentProcessInstanceId=` уже есть).
