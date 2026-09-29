# ADR-1 — Multi-tenant authorization (ownership + service accounts)

- **Статус:** Proposed (ожидает ратификации CTO)
- **Дата:** 2026-07-11
- **Контекст-владелец:** CTO (governance)
- **Заменяет:** отсутствующую модель изоляции (сейчас после WO-SEC-1 любой аутентифицированный видит и МЕНЯЕТ всё)

---

## 1. Контекст и проблема

ZBPM будут использовать **разные клиенты**. Сейчас:
- `JwtAuthFilter` — единственная точка энфорсмента: `/users/**` = роль `ADMIN`, весь data-API = любой валидный токен.
- `RuntimeResource`: `startProcessInstance`, `completeServiceTask`, `failServiceTask`, `resolveIncident`, `cancelProcessInstance` — **без авторизации**; `completeUserTask` — только `checkAssignee` (SEC-5), candidate group не проверяется.

Итог: **любой аутентифицированный пользователь стартует/отменяет/завершает ЧУЖИЕ процессы.** Изоляции нет.

Нужно: каждый клиент управляет только своим процессом; чтение — открыто; машинные интеграции (1С, SAP, воркеры, боты) ходят без человеческого логина и без refresh-токенов.

## 2. Решение — три плоскости доступа

Доступ разведён на три независимых плоскости. Их смешение было корнем путаницы.

| Плоскость | Кто | Механизм | Правило |
|---|---|---|---|
| **Владение / дизайн** | люди | JWT (httpOnly cookie) | только `ProcessMember` |
| **Исполнение человеческих задач** | assignee / candidate group | JWT | ортогонально членству |
| **Интеграция / исполнение** | Service Accounts | API-ключ `zbpm_sk_…` | `permissions[]` |

### 2.1. Чтение — ОТКРЫТО любому аутентифицированному

Список процессов, открыть процесс, BPMN, версии, история, инстансы, задачи, логи — доступны любому валидному Principal (человек ИЛИ service account). **Фильтрации list/GET нет** — это осознанное решение, оно радикально упрощает энфорсмент (проверки только на запись).

### 2.2. Изменение — только `ProcessMember`

Роли **на процесс** (не глобальные):

- **OWNER** — полный контроль: правит модель, деплой/версии, **управляет участниками и service-account'ами своего процесса** (выпуск/отзыв ключей), удаление процесса.
- **DESIGNER** — изменение BPMN, деплой, публикация версий. Не трогает участников и ключи.
- *(другие роли — позже, при необходимости; `VIEWER` НЕ вводим — чтение и так открыто).*

### 2.3. Исполнение человеческих задач — вне `ProcessMember`

`completeUserTask` разрешён, если Principal:
- **человек** = `assignee` задачи **или** входит в её **candidate group** (расширяем текущий `checkAssignee`, changeset 007 `assignees`);
- **service account** с правом `COMPLETE_USER_TASK` в рамках процесса задачи (бот закрывает от имени человека, опц. заголовок `X-On-Behalf-Of` пишется в историю);
- **SUPER_ADMIN** — bypass.

### 2.4. Машинный доступ — Service Account + permissions[]

Первоклассная сущность (не «ключ на юзере»). Набор прав:
`{ START, FETCH_LOCK, COMPLETE_SERVICE_TASK, COMPLETE_USER_TASK, CORRELATE_MESSAGE }`
(`READ` не нужен — валидный ключ уже аутентифицирован, чтение открыто.)

Пример: `sap` → `[COMPLETE_SERVICE_TASK]`; `telegram` → `[START, COMPLETE_USER_TASK]`; `java-worker` → `[FETCH_LOCK, COMPLETE_SERVICE_TASK]`.

## 3. Два креденшла — не путать

| Кто | Аутентификация | Где живёт | Почему не утекает |
|---|---|---|---|
| Человек в UI | JWT в **httpOnly** cookie `zbpm_token` | браузер | httpOnly → JS/XSS не читает (SEC-6/9..12) |
| Воркер / интеграция | **API-ключ** `zbpm_sk_…` в `Authorization: Bearer` | сервер интеграции | никогда не в браузере |

**Жёсткое правило:** браузер → только cookie-сессия; сервер-к-серверу → только API-ключ. **API-ключ никогда не кладётся во фронтенд.** Если клиентскому фронту нужно управлять процессом — либо его пользователи получают ZBPM-логины (JWT), либо его бэкенд держит ключ и проксирует. Ключ в JS фронта = утечка, запрещено.

## 4. Модель данных

```
Process                       -- реестр, 1 строка на processDefinitionKey
 ├── id (uuid, pk)
 ├── definition_key (unique)  -- связь со ВСЕМИ версиями в process_definitions
 ├── name
 └── created_at

ProcessMember                 -- человеческий доступ (JWT)
 ├── process_id  → Process.id
 ├── user_id     → ui_users.id
 ├── role        -- OWNER | DESIGNER
 ├── added_by    -- кто выдал (аудит)
 ├── added_at
 └── UNIQUE(process_id, user_id)

ServiceAccount                -- машинный доступ, первоклассный
 ├── id (uuid, pk)
 ├── process_id → Process.id  -- scope
 ├── name                     -- '1c' | 'sap' | 'java-worker' | ...
 ├── key_hash (unique)        -- SHA-256, НИКОГДА плейнтекст
 ├── prefix                   -- 'zbpm_sk_' + первые символы, для идентификации/скана
 ├── permissions              -- набор из §2.4
 ├── created_at
 ├── last_used_at
 ├── expires_at (nullable)    -- по умолчанию NULL = бессрочно
 └── revoked_at (nullable)    -- единственный kill-switch
```

**Владение — по `definition_key`** (все версии одного процесса), не по id версии — иначе redeploy сбрасывал бы доступы.

**Глобальная роль** (`ui_users.role`, varchar(32)): `SUPER_ADMIN` | `USER`. Существующий `ADMIN` мигрирует в `SUPER_ADMIN`. `ProcessMember.role` (OWNER/DESIGNER) — ортогонален глобальной роли.

## 5. Единая точка решения

`JwtAuthFilter` резолвит любой `Authorization: Bearer …` / cookie в единый `Principal`:
- JWT/cookie → `Principal(type=USER, userId, globalRole)`
- `zbpm_sk_…` → hash-lookup → `Principal(type=SERVICE_ACCOUNT, processId, permissions)` (проверка `revoked_at IS NULL`, `expires_at`)

Одна функция:
```
canOperate(principal, process, action):
  SUPER_ADMIN                          → allow
  USER:  action ∈ {deploy, members, keys, delete} → ProcessMember(process,user).role соответствует
  SERVICE_ACCOUNT: action ∈ permissions AND sa.process_id == process.id → allow
  else                                 → 403

canCompleteUserTask(principal, task):
  SUPER_ADMIN                          → allow
  USER: assignee==user OR user∈candidateGroup(task) → allow
  SERVICE_ACCOUNT: COMPLETE_USER_TASK ∈ permissions AND task.process==sa.process → allow
  else                                 → 403
```

## 6. Требования безопасности к API-ключу (non-negotiable)

Статический бессрочный ключ = если утёк, валиден до ручного отзыва. На стороне ZBPM обязательно:
1. **Хеш в БД** (SHA-256), плейнтекст показывается **один раз** при создании.
2. **Ревокация — единственный kill-switch** (экспирации по умолчанию нет): проверка `revoked_at` на каждом запросе.
3. **Ротация без даунтайма** — допускаются **несколько активных ключей на один SA**.
4. **Опц. `expires_at`** — по умолчанию NULL (бессрочно), но можно задать срок.
5. **Только заголовок** `Authorization`, никогда в URL/query. **Rate-limit + audit** каждого использования.
6. **Префикс** `zbpm_sk_` — для идентификации и secret-scanning (gitleaks).

## 7. Ратифицированные решения (зафиксировано в диалоге с CTO)

1. Единица владения = **Process Definition (по key)**. ✅
2. Управление: **self-service** — пользователь деплоит модель, становится OWNER; SUPER_ADMIN — надо всем. ✅
3. Чтение — **открыто всем** аутентифицированным. ✅
4. Роли людей — **OWNER + DESIGNER** (VIEWER отклонён). ✅
5. Исполнение — **assignee / candidate group**, вне членства. ✅
6. Машины — **ServiceAccount + permissions[]**, статический ключ **без refresh**. ✅
7. OWNER управляет участниками и ключами **своего** процесса. ✅ *(дефолт CTO; при возражении — сузить до SUPER_ADMIN)*

## 8. Последствия

**Плюсы:** простая модель (чтение открыто → энфорсмент только на запись); машины без refresh; аудит по имени SA; изоляция записи.
**Минусы / риски:**
- Пронизывающая авторизация на всех mutating-эндпоинтах — **риск неполного охвата** (Fable уже ловил такое). Требуется исчерпывающий список точек + негативные тесты на каждую.
- Статические ключи требуют дисциплины ротации/отзыва.
- Чтение открыто = **любой аутентифицированный видит данные всех процессов** (в т.ч. переменные). Если позже понадобится закрыть чтение — это отдельный ADR (вернуть роль на чтение).

## 9. План реализации

ADR → серия WO (см. `governance/workorders/`): **WO-MT-1** схема · **WO-MT-2** Principal + API-key auth · **WO-MT-3** энфорсмент записи · **WO-MT-4** управление ключами · **WO-MT-5** управление членством · **WO-MT-6** фронтенд.

**Контрольная точка:** после WO-MT-3/4 — независимый аудит (Fable) именно на изоляцию записи: «достучись до чужого процесса всеми способами». Один непокрытый эндпоинт = дыра во всей модели.
