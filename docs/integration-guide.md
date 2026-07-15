# ZorroBPM — Гайд по интеграции внешних систем

Как подключить внешнюю систему (портал, KT Docs, любой сервис №2/3/4…) к движку ZorroBPM: запускать процессы,
показывать формы, вести инбокс задач, получать события. Аудитория — интеграторы и фронт-команда.

> **Главный принцип.** ZBPM — **headless BPM-движок за вашей системой**. Число систем движку безразлично:
> новая система = ещё один сервис-ключ. **Per-system кода в движке нет.** Формы и их валидация живут в вашей
> системе (external forms) — движок несёт только `formKey` + переменные.

---

## 1. Модель аутентификации

Два способа доступа, выбор — по тому, кто действует:

| Кто действует | Способ | Заголовок |
|---|---|---|
| **Внешняя система** (машина, от лица многих сотрудников) | **сервис-ключ** (owner-scoped) | `Authorization: Bearer zbpm_sk_…` |
| **Человек в вашем UI** (у него есть аккаунт ZBPM) | **JWT** (логин) | `Authorization: Bearer <jwt>` |

- Ключ имеет **гранты на процессы**: `START`, `COMPLETE_USER_TASK` (+ `CORRELATE_MESSAGE`,
  `FETCH_LOCK`/`COMPLETE_SERVICE_TASK` для внешних воркеров). Заводит и раздаёт гранты **super-admin**.
- **Ключ держит только backend вашей системы. Никогда — браузер.**
- **Чтение открыто** любому аутентифицированному; **запись** гейтится грантом/ролью.
- Все mutating-операции пишутся в **audit_log**.
- Один ключ **на систему/тенанта** (границу доверия), **не** на процесс и **не** на сотрудника.
  100 процессов под одной системой = один ключ с грантами на них.

**CORS.** Если ваш UI ходит в движок **из браузера** напрямую — добавьте его origin в
`ZORROBPM_CORS_ORIGINS` (прод), иначе запросы не пройдут. Для server-to-server (backend с ключом) CORS не нужен.

---

## 2. Две формы интеграции

### 2a. Система-за-порталом (пример: KT Docs) — рекомендуемая для «всё внешнее»
Сотрудники — пользователи **вашей** системы, не ZBPM. Ваш backend держит **один сервис-ключ** и действует
от их лица. Личность человека едет как **данные**, не как логин:
- переменные процесса (`employeeId`, `managerId`…),
- заголовок **`X-On-Behalf-Of: <externalUserId>`** (для аудита; на старте пишется в `initiator` инстанса).

### 2b. Внешний человеческий UI (люди — пользователи ZBPM)
Ваш SPA логинит человека: `POST /auth/login` → **JWT** (+ `POST /auth/refresh`). Браузер держит JWT, не ключ.
- Добавьте origin в CORS.
- Обработайте **`403 {"code":"PASSWORD_CHANGE_REQUIRED"}`** — показать форму смены пароля
  (`/auth/me` и смена пароля exempt; данные-API закрыты до смены).

---

## 3. Формы — external (движок ничего не рендерит)

У user-task/стартового события в BPMN задаётся `formKey` (строка). Движок его **только хранит и отдаёт** —
**не рендерит и не валидирует**. Это модель «external form» (паритет с Camunda external form).

```
GET /user-tasks/{id}  →  { formKey, variables, assignee, candidateGroups, … }
```
Ваша система по `formKey` находит свою форму, рисует её, **валидирует на своей стороне**, собирает данные,
и завершает задачу. 100 процессов = 100 определений форм (данные у вас), **не** 100 приложений: один
generic-рендерер, управляемый `formKey`.

> Валидация стартовой формы — тоже на вашей стороне (движок переменные на старте не валидирует, как и любой
> BPMN-движок). Для жёсткого серверного гейта на старте нужна отдельная фича (в бэклоге, не обязательна).

---

## 4. Основные потоки (с примерами вызовов)

### 4.1. Старт процесса
```http
POST /process-instances
Authorization: Bearer zbpm_sk_…
X-On-Behalf-Of: emp42          # опционально — кто из людей инициировал
Content-Type: application/json

{ "processDefinitionKey": "vacation",
  "variables": [
    { "name": "employeeId", "type": "STRING", "value": "emp42" },
    { "name": "managerId",  "type": "STRING", "value": "mgr-7" },
    { "name": "days",       "type": "STRING", "value": "5" }
  ] }
```
`initiator` инстанса = `X-On-Behalf-Of` (если задан). Можно стартовать и по `processDefinitionId`.

### 4.2. Инбокс задач
```http
GET /user-tasks?assignee=mgr-7          # мои задачи
GET /user-tasks?candidateGroup=hr       # групповой инбокс
GET /user-tasks?processInstanceId=…      # задачи инстанса
```
Фильтры `UserTaskQuery`: `assignee`, `candidateGroup`, `candidateUser`, `processInstanceId`, `completed`, `assigned`.
Ответ — постраничный. Затем `GET /user-tasks/{id}` → `formKey` + `variables` для рендера формы.

### 4.3. Завершение задачи
```http
POST /user-tasks/{id}/complete
Authorization: Bearer zbpm_sk_…
X-On-Behalf-Of: mgr-7          # кто согласовал → в audit_log
Content-Type: application/json

{ "variables": [ { "name": "approved", "type": "STRING", "value": "true" } ] }
```
Право закрыть: **assignee** задачи, **член её candidate group**, или super-admin. Иначе `403`.

### 4.4. Уведомления «появилась задача» — два подхода
- **Поллинг (просто):** периодически `GET /user-tasks?assignee=|candidateGroup=`. Годится для реконнекта/сверки.
- **Push через события — ⚠️ ПОКА НЕ РЕАЛИЗОВАНО (планируется).** Доменные события
  (`UserTaskInstanceCreated`, `…Completed`, `ProcessInstanceCreated/Completed`, incidents) в коде движка
  **пока только логируются** — публикация в RabbitMQ закомментирована (`ProcessEngineEventsServiceImpl`,
  находка аудита F13). **Не полагайтесь на подписку до реализации.** Актуальный механизм уведомлений —
  **поллинг** `GET /user-tasks`. (Транзакционный outbox → RabbitMQ реально работает только для диспетча
  **service-task** воркерам, не для доменных событий.)
- **Websocket** — на **вашей** стороне: пока — поверх поллинга; после реализации F13 — поверх RabbitMQ-событий.
  В самом ZBPM websocket'а нет.

### 4.5. Внешняя работа (service-task) и корреляция
- Внешний воркер: `fetch-lock` через RabbitMQ → работа → `POST /service-tasks/{id}/complete` (гранты
  `FETCH_LOCK`/`COMPLETE_SERVICE_TASK`).
- Возобновление процесса внешним событием (message catch): correlate-запрос (грант `CORRELATE_MESSAGE`).

---

## 5. Маршрутизация согласований

`assignee` и `candidateGroups` в BPMN могут быть **выражениями** — движок резолвит их из переменных инстанса
при создании задачи (WO-INT-1):

| В BPMN | Резолв |
|---|---|
| `assignee="${managerId}"` | значение переменной `managerId` |
| `assignee="=managerId + \"_lead\""` | FEEL-выражение (Camunda FEEL) |
| `candidateGroups="${deptGroup}"` | значение переменной |
| `assignee="user1"` | литерал (как есть) |

- **Членство в группах:** таблица `user_group` (user ↔ group). Член candidate group задачи может её закрыть
  (WO-MT-3b). Раздаётся отдельно.
- **Общий неймспейс id.** `assignee`/`candidateGroup` — строки. Чтобы задача показалась нужному человеку —
  все системы должны договориться о **едином формате id** (напр. `employeeId`, имена групп). Если каждая
  система владеет своими процессами и людьми — пересечений нет. Для **сквозных** процессов нужен общий
  справочник id. Это **соглашение о данных**, не код движка.

---

## 6. End-to-end пример: «Отпуск» через KT Docs

```
Старт (сотрудник):
  1. Сотрудник в KT Docs заполняет форму «Отпуск» (форма + валидация — в KT Docs).
  2. «Отправить» → backend KT Docs (сервис-ключ):
       POST /process-instances  X-On-Behalf-Of: emp42
       { processDefinitionKey:"vacation", variables:{ employeeId, managerId, days } }
  3. Инстанс создан, initiator=emp42.

Согласование (руководитель):
  4. Процесс дошёл до user-task «Согласование», assignee резолвится в managerId.
  5. KT Docs узнаёт о задаче: поллинг GET /user-tasks?assignee=mgr-7 (RabbitMQ-события — план, F13).
  6. В инбоксе руководителя всплывает задача → GET /user-tasks/{id} → formKey+vars → KT Docs рисует форму.
  7. «Согласовать» → POST /user-tasks/{id}/complete  X-On-Behalf-Of: mgr-7  { approved:true }
  8. Процесс идёт дальше (HR, приказ). audit_log: принципал=ключ, on_behalf_of=mgr-7.
```
Всё через **один** сервис-ключ KT Docs. Сотрудники движок не трогают.

---

## 7. Сравнение с Camunda 8

| Задача | Camunda 8 | ZorroBPM |
|---|---|---|
| Auth системы | OAuth2 client-credentials (Identity → JWT) | сервис-ключ `zbpm_sk_` (owner-scoped) |
| Старт | `CreateProcessInstance` (gRPC/REST) | `POST /process-instances` |
| **External form** | `zeebe:formDefinition externalReference` (строка) | `formKey` (строка) |
| Рендер/валидация движком | нет | нет |
| Инбокс | `/user-tasks/search`, native tasks | `GET /user-tasks?…` |
| Динамический assignee | FEEL | `${var}` / FEEL |
| Push событий | exporters → ES/Kafka | поллинг (домен-события в RabbitMQ — план, F13) |
| Вход без кода | inbound Connectors (webhook/Kafka) | — (на будущее) |
| Websocket в браузер | нет (Tasklist поллит) | нет (на стороне вашей системы) |

По external-формам мы уже эквивалентны C8: `formKey`-passthrough + complete через API.

---

## 8. Справочник эндпоинтов

| Метод | Путь | Назначение |
|---|---|---|
| POST | `/auth/login` | JWT для человека (внешний UI) |
| POST | `/auth/refresh` | обновить JWT |
| GET  | `/auth/me` | текущий пользователь (+ `forcePasswordChange`) |
| POST | `/process-instances` | старт инстанса (+ `X-On-Behalf-Of`) |
| POST | `/process-instances/{id}/cancel` | отмена инстанса |
| GET  | `/process-instances` `/{id}` | список / один инстанс |
| GET  | `/user-tasks` | инбокс (фильтры `UserTaskQuery`) |
| GET  | `/user-tasks/{id}` | задача: `formKey` + `variables` |
| POST | `/user-tasks/{id}/complete` | завершить задачу (+ `X-On-Behalf-Of`) |
| GET  | `/process-definitions` `/{id}` | определения процессов |
| POST | `/service-tasks/{id}/complete` | завершить внешнюю работу |

> Прод: внешний прокси отдаёт бэкенд в **корне** (`https://zorro.i-smet.kz/process-instances`), SPA — под `/ui/`.

---

## 9. Что учесть (ограничения на сегодня)

- **Websocket** — нет нативного; стройте на своей стороне поверх RabbitMQ-событий.
- **`${a.b}`** (вложенный путь в `${}`) не резолвится — для богатых выражений используйте `=FEEL`.
- **Self-service смена пароля** для forced non-admin — эндпоинта нет (forced-флаг ставится только
  бутстрап-админу). Фронт `PASSWORD_CHANGE_REQUIRED` — в бэклоге (нужен до fresh-install).
- **Серверная валидация стартовой формы** (400 на невалидный старт) — не реализована; валидируйте в своей форме.
- **Секреты/значения переменных в аудит не пишутся** — только идентификатор действия и `on_behalf_of`.
