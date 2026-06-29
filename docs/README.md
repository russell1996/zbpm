# ZorroBPM — Единый трекер и дорожная карта

> **Это единственный источник истины** по состоянию и планам проекта. Параллельных
> роадмапов/бэклогов быть не должно. Состояние ниже отражает **код** (проверено grep/find/запуском),
> а не старые доки. Дата сверки: **2026-06-29**.
>
> Корневой `../README.md` — пользовательская документация продукта. Этот файл — внутренний
> governance-трекер (что есть по коду + одна дорожная карта).

---

## 1. Что это

ZorroBPM CE — лёгкий BPMN 2.0 движок. Spring Boot 4.0.5 / Java 21 / PostgreSQL 16 / RabbitMQ 3.13 /
Camunda FEEL 1.19.3. Исполнение — токенный обход графа определения процесса. 10 Maven-модулей +
`zorrobpm-frontend` (Vue 3 + Vite + TS). Версия `0.7.17-SNAPSHOT`, Apache 2.0.
Прод (single-node): `https://zorro.i-smet.kz`.

## 2. Текущее состояние (по коду)

| Область | Состояние | Подтверждение |
|---|---|---|
| BPMN-исполнение | Зрелый MVP: события (message/timer/signal/error/escalation/link/compensation/conditional/cancel), все шлюзы, multi-instance, call activity, embedded/event subprocess, transaction | `ActivityServiceImpl`, `BpmnParseServiceImpl`, `BpmnElementType` (47 типов) |
| Таймеры | date/duration/**timeCycle** (ISO `R[n]/<dur>` + Spring cron), repeat/reschedule | `scheduler/TimerExpressions.java` (✅ верифицировано — старая память «не поддерживается» **устарела**) |
| DMN | Чтение + evaluate API, собственный движок | `DmnContract` (`/dmn`, `/dmn/{id}`, `/dmn/{id}/evaluate`) |
| Query API | Single-entity GET (instance/task/incident), `/process-instances/{id}/activities`, timer-jobs, message-subscriptions, серверные фильтры/сортировка | `QueryContract.java:33-61` |
| Frontend | MVP: все основные страницы, i18n (ru/en/kz), темы, BPMN-вьювер с подсветкой/токенами. Часть разделов на моках (см. U-track) | `zorrobpm-frontend/` |
| Безопасность | ⚠️ Data API **открыт**; защищены только `/auth/me` + `/users/**`; дефолтный jwt-secret; CORS `*` | `JwtAuthFilter.java:30`, `TokenService.java:29`, `WebConfiguration.java:13` |
| Кластер/HA | ⚠️ Не готов: таймеры без leader election; версионирование через JVM `synchronized` | `TimerScheduler`, `ProcessDefinitionServiceImpl` |

**Оценки зрелости (аудит):** BPMN 8/10 · DMN 7/10 · Production 5/10 · Cluster 2/10 · Enterprise 2/10.
**Вердикт:** функциональный single-node MVP за доверённым шлюзом; для multi-node/enterprise нужны Фаза 1 (безопасность) и Фаза 3 (HA). Блокеры прод-запуска: **T1, T4, T2**.

## 3. Дорожная карта (единственная)

Коды: `T*` — движок/бэкенд/инфра; `U*` — frontend/UX; `C*` — Camunda 8 остаточная совместимость.
Каждая задача при исполнении трассируется к коду наблюдения и получает фальсифицируемый тест + proof-of-failure (см. DoD).

### Фаза 1 — Критические исправления (P0, блокеры прода)

| ID | Задача | Файлы | Acceptance (объективно) |
|----|--------|-------|--------------------------|
| **T1** | Защита Data API (аутентификация/API-ключ на всех эндпоинтах) | `JwtAuthFilter`, новый `TenantContextFilter`, `engine`, `contract` | grep: ни один `/process-*`,`/incidents`,`/variables`,`/dmn`,`/timer-jobs` не отдаёт 200 без токена; тест 401 на защищённый путь — красный до фикса |
| **T4** | Security hardening: fail-fast на дефолтном `jwt-secret` в prod, профильный CORS, форс-смена admin | `TokenService`, `WebConfiguration`, `UiUserBootstrap` | prod-профиль падает при дефолтном secret (тест); CORS не `*` в prod; флаг `forcePasswordChange` |
| **T2** | Атомарный timer-fire + leader election (нет дублей в кластере) | `TimerScheduler`, `TimerJobExecutor`, `DBServiceImpl.markTimerJobFired`, `TimerJobRepository` | `SELECT … FOR UPDATE SKIP LOCKED` или conditional UPDATE; конкурентный тест на 2 «ноды» не даёт дублей |

### Фаза 2 — Production Readiness (P1–P2)

| ID | Задача | Файлы | Acceptance |
|----|--------|-------|-----------|
| **T3** | Распределённое версионирование (заменить JVM `synchronized(VERSION_LOCK)`) | `ProcessDefinitionServiceImpl`, `ProcessDefinitionRepository` | DB advisory lock / `FOR UPDATE` по ключу; уникальный `(code,version)` с ретраем; конкурентный тест |
| **T8** | Observability (Actuator/health, Micrometer) + hot-индексы БД | `engine`, Liquibase | `/actuator/health` up; индексы `variables(process_instance_id,scope_id)`, `activities(process_instance_id,status)`, `timer_jobs(fired,due_at)`, `message_subscriptions(consumed,message_name)` |
| **T7** | `assignee`/`formKey` persistence в `createUserTask` (сейчас парсятся, но не пишутся) | `DBServiceImpl.createUserTask`, маппер | строка `user_tasks` содержит assignee/formKey из модели; фильтр assignee работает (тест красный до фикса) |
| **T6** | DMN hit policies COLLECT/PRIORITY/RULE ORDER/OUTPUT ORDER + валидация UNIQUE/ANY | `DmnServiceImpl.evaluate` | матрица тестов по политикам против ожидаемого набора правил (оракул — спецификация DMN) |

### Фаза 3 — Масштабирование и HA

- Полноценный leader election / ShedLock; архивация/cleanup истории активностей; тюнинг пула и интервала timer-polling.

### Фаза 4 — Enterprise

| ID | Задача | Источник | Acceptance |
|----|--------|----------|-----------|
| **T5** | Мультитенантность (clients + api_keys, изоляция данных, скоуп корреляции message/signal) | дизайн: [multi-tenant-authorization-plan.md](multi-tenant-authorization-plan.md) | арендатор видит/трогает только свои определения и рантайм; кросс-тенантный тест на изоляцию |
| **T9** | Тенантные воркеры RabbitMQ (изоляция исполнения) | — | per-tenant очереди; тест маршрутизации |
| — | History/Audit API, process-instance modification, RBAC/ACL, batch | — | — |

### UI / Frontend track (U*)

| ID | Задача | Состояние | Acceptance |
|----|--------|-----------|-----------|
| **U1** | Снять разделы Timers/Messages с моков → подключить к API (`/timer-jobs`, `/message-subscriptions` уже есть) | бэкенд готов, фронт на моках | в `services/` нет mock для timers/messages; страницы рендерят данные API |
| **U2** | Admin/Users реальные (зависит от T1/T5 авторизации) | мок | CRUD пользователей через бэкенд; нет `mock/userService` |
| **U3** | Вкладка «вызванные подпроцессы» в `ProcessInstanceDetail` (API `?parentProcessInstanceId=` есть) | не сделано | вкладка показывает дочерние инстансы |
| **U4** | Формы переменных: типы DOUBLE/JSON | частично | ввод/валидация DOUBLE и JSON |
| **U5** | Real-time обновления (WebSocket/SSE) | нет | live-обновление дашборда/списков без polling |
| **U6** | BPMN Designer (viewer → editor), фазовое внедрение | только viewer | деплой из редактора; см. архивную визию |
| **U7** | Process Analytics на реальных данных (charts, SLA) | моки/частично | графики из агрегатов API, не моков |

### Остаточная совместимость с Camunda 8 (C*) — детали в [camunda8-compatibility.md](camunda8-compatibility.md)

| ID | Задача | Примечание |
|----|--------|-----------|
| **C1** | Компенсация в scope встроенного подпроцесса | сейчас compensate-all + targeted работают на корне |
| **C2** | Multi-instance `outputCollection`/`outputElement` | агрегация результатов MI |
| **C3** | Ограниченный повтор `R<n>` timeCycle (счётчик в `timer_jobs`) | сейчас бесконечный/одиночный |

> `conditionalEvent` и `cancelEvent` — **проектные расширения**, в Camunda 8 отсутствуют by design; это не гэпы, а заявленная сверхподдержка. Не «исправлять».

## 4. Справочные документы (KEEP)

- [deployment.md](deployment.md) — runbook (CI/CD, Docker, прод-топология).
- [camunda8-compatibility.md](camunda8-compatibility.md) — матрица C8-совместимости по конструкциям (as-is).
- [multi-tenant-authorization-plan.md](multi-tenant-authorization-plan.md) — дизайн T5.
- [_audit/doc-inventory.md](_audit/doc-inventory.md) — реестр решений по документации (Mission 0).
- [analysis/](analysis/README.md) — **reverse-engineering анализ движка по коду** (Mission 1): as-is по областям + матрица проблем с кодами P/M/B/T/F/S/SEC. Каждая задача роадмапа трассируется к этим кодам.

## 5. Governance дока

Один трекер (этот файл), один роадмап (раздел 3). Новые планы не плодим — изменения вносим сюда.
Архитектурные решения фиксируются ADR. Снятые доки и причина — в [_audit/doc-inventory.md](_audit/doc-inventory.md).
