# ZorroBPM — Дорожная карта (по результатам независимого аудита)

Источник: независимая проверка утверждений из `.mimocode/plans/1782166325332-tidy-sailor.md` по коду.
Документ отражает **текущее** состояние: часть пунктов уже закрыта в ходе работ (отмечено ✅ DONE).

Дата: 2026-06-23.

---

## Сводка задач

| ID | Задача | Крит. | Слож. | Приоритет | Статус |
|----|--------|-------|-------|-----------|--------|
| **DONE-1** | DMN read/evaluate REST API (`GET /dmn`, `/dmn/{id}`, `POST /dmn/{id}/evaluate`) | — | M | — | ✅ DONE |
| **DONE-2** | Инцидент-resolve больше не двоит токен (дубли user/service task) | Critical | M | — | ✅ DONE |
| **DONE-3** | Статус задач (CANCELLED/ERROR) в API/UI + фильтр по статусу активности | High | M | — | ✅ DONE |
| **DONE-4** | UI: resolve инцидента в инстансе, refresh статуса, flush переменных, бейджи | Medium | M | — | ✅ DONE |
| **T1** | Защита Data API (все эндпоинты под аутентификацией / API-ключ) | Critical | L | **P0** | OPEN |
| **T2** | Атомарный timer-fire + leader election (нет дублей таймеров в кластере) | Critical | M | **P0** | OPEN |
| **T3** | Распределённое версионирование (заменить JVM `synchronized(VERSION_LOCK)`) | High | M | **P1** | OPEN |
| **T4** | Security hardening: fail-fast на дефолтном `jwt-secret`, CORS в prod, форс-смена admin | High | S | **P0** | OPEN |
| **T5** | Мультитенантность (clients + api_keys, изоляция данных) — `docs/multi-tenant-authorization-plan.md` | High | XL | **P1** | OPEN (план готов) |
| **T6** | DMN hit policies COLLECT/PRIORITY/RULE ORDER/OUTPUT ORDER + валидация UNIQUE/ANY | Medium | M | **P2** | OPEN |
| **T7** | User-task `assignee`/`formKey` persistence (сейчас парсятся, но не пишутся в `user_tasks`) | Medium | S | **P2** | OPEN |
| **T8** | Observability (Actuator/health, Micrometer) + hot-индексы БД | Medium | M | **P2** | OPEN |
| **T9** | Тенантные воркеры RabbitMQ (изоляция исполнения) | Low | L | **P3** | OPEN |

---

## Детализация открытых задач

### T1 — Защита Data API (P0, Critical)
- **Причина:** `JwtAuthFilter.isProtected()` закрывает только `/auth/me` и `/users/**`; `/process-*`, `/incidents`, `/variables`, `/dmn`, `/timer-jobs`, `/message-subscriptions` — открыты.
- **Решение:** API-ключ на клиента (см. T5) или расширить фильтр на все эндпоинты + бэкенд-проверки. Флаг включения, чтобы не сломать Java-клиент.
- **Затрагивает:** `zorrobpm-rest` (`JwtAuthFilter`, новый `TenantContextFilter`), `engine`, `contract`.

### T2 — Таймеры в кластере (P0, Critical)
- **Причина:** `TimerScheduler.fireDueTimers()` — `@Scheduled` без координации; `findDueTimerJobs()` (SELECT) + `markTimerJobFired()` (load→set→save) не атомарны; нет leader election/ShedLock/`SKIP LOCKED`.
- **Решение:** `SELECT … FOR UPDATE SKIP LOCKED` или conditional `UPDATE … WHERE fired=false`; или ShedLock/leader election.
- **Затрагивает:** `TimerScheduler`, `TimerJobExecutor`, `DBServiceImpl.markTimerJobFired`, `TimerJobRepository`, аналогично timer-start.

### T3 — Версионирование (P1, High)
- **Причина:** `ProcessDefinitionServiceImpl`: `private static final Object VERSION_LOCK; synchronized(VERSION_LOCK)` — лок локален для JVM, в кластере не работает.
- **Решение:** DB advisory lock / `SELECT … FOR UPDATE` по ключу + обработка уникального constraint `(code,version)` с ретраем.
- **Затрагивает:** `ProcessDefinitionServiceImpl`, `ProcessDefinitionRepository`.

### T4 — Security hardening (P0, High)
- **Причина:** `TokenService` дефолтный `jwt-secret=change-me-…`; `WebConfiguration` CORS `allowedOriginPatterns("*")` + `allowCredentials(true)`; `UiUserBootstrap` создаёт `admin/admin` без форс-смены.
- **Решение:** fail-fast при дефолтном secret в prod-профиле; профильный CORS; флаг `forcePasswordChange`.
- **Затрагивает:** `TokenService`, `WebConfiguration`, `UiUserBootstrap`.

### T5 — Мультитенантность (P1, XL)
- Полный план: `docs/multi-tenant-authorization-plan.md`. Критический путь: схема → контекст-фильтр → владение определениями → изоляция рантайма (вкл. скоуп корреляции message/signal).

### T6 — DMN hit policies (P2, Medium)
- **Причина:** `DmnServiceImpl.evaluate()` делает `break` на первом совпадении для любой политики.
- **Решение:** ветвление по `hitPolicy` (COLLECT — все совпадения/агрегация; PRIORITY/RULE ORDER/OUTPUT ORDER — порядок; UNIQUE/ANY — валидация).

### T7 — assignee/formKey persistence (P2, Medium)
- **Причина:** `DBServiceImpl.createUserTask` не переносит `assignee`/`formKey` из модели в строку `user_tasks` → фильтры assignee/assigned неработоспособны.
- **Решение:** пробросить из `BpmnElementModel` в `createUserTask` + маппер.

### T8 — Observability + индексы (P2, Medium)
- Actuator/health, Micrometer; индексы: `variables(process_instance_id, scope_id)`, `activities(process_instance_id, status)`, `timer_jobs(fired, due_at)`, `message_subscriptions(consumed, message_name)`.

---

## Дорожная карта (этапы)

### Этап 1 — Критические исправления (P0)
- **T1** Защита Data API
- **T4** Security hardening (secret/CORS/admin)
- **T2** Атомарный timer-fire

### Этап 2 — Production Readiness (P1–P2)
- **T3** Распределённое версионирование
- **T8** Observability + индексы
- **T7** assignee/formKey persistence
- **T6** DMN hit policies

### Этап 3 — Масштабирование и HA
- Полноценный leader election / ShedLock
- Архивация/cleanup истории активностей
- Тюнинг пула соединений и интервала timer-polling

### Этап 4 — Enterprise Features
- **T5** Мультитенантность (clients + API-ключи + изоляция)
- **T9** Тенантные воркеры RabbitMQ
- History/Audit API, process-instance modification, RBAC/ACL, batch-операции

---

## Итоговые оценки (независимый аудит, текущее состояние)

| Критерий | Оценка |
|----------|--------|
| BPMN Compatibility | 8/10 |
| DMN Compatibility | 7/10 |
| Production Readiness | 5/10 |
| Cluster Readiness | 2/10 |
| Enterprise Readiness | 2/10 |

**Вердикт:** функционально зрелый MVP для single-node за доверённым шлюзом; для enterprise/multi-node
требуется Этап 1 (безопасность) и Этап 3 (HA). Главные блокеры прод-запуска: **T1, T4, T2**.
