# ZorroBPM CE — Roadmap & Work Plan (АРХИВ, не актуально)

> ⚠️ **Устарело.** Ссылается на WO-SEC-1/2/3 как на невыполненные — они смёржены давно. Живой источник
> приоритетов — [`governance/workorders/_index.md`](../../governance/workorders/_index.md). Файл оставлен
> для истории (изначальная структура фаз до того, как всё переехало в WO-индекс).
>
> Управление: [`/governance/`](../../governance)

---

## Статус продукта

**Stack:** Spring Boot 4.0.5 / Java 21 / PostgreSQL 16 / RabbitMQ 3.13 / Vue 3 + Vite + Pinia  
**Modules:** `zorrobpm-contract`, `zorrobpm-engine`, `zorrobpm-rest`, `zorrobpm-frontend`  
**Tests:** 55+ IT в engine, юнит-тесты на ключевые сервисы. Тесты на H2 (не PostgreSQL).

**Вердикт:** Движок функционально зрелый. **Не готов к продакшену** — критические уязвимости безопасности.

---

## Фаза 0 — Production Blockers (блокируют прод) 🔴

Все задачи CRITICAL/HIGH severity. Ни одна из них не требует ADR — прямая реализация.

| WO | Задача | Findings | Файлы (scope) |
|---|---|---|---|
| [WO-SEC-1](../../governance/archive/workorders/WO-SEC-1-api-auth.md) | ✅ Реализовано. Нужны только IT-тесты (`requireApiAuth=true`, GET без токена → 401) | CRITICAL-2 | `JwtAuthFilter.java` (тесты) |
| [WO-SEC-2](../../governance/archive/workorders/WO-SEC-2-hardening.md) | Hardening: CORS + default secret + admin/admin | CRITICAL-1,3 | `WebConfiguration.java`, `UiUserBootstrap.java`, `TokenService.java` |
| [WO-SEC-3](../../governance/archive/workorders/WO-SEC-3-validation-errors.md) | DTO validation + global error handler | HIGH-4,5; GAP-8 | все resources, новый `GlobalExceptionHandler` |

---

## Фаза 1 — Reliability (надёжность)  🟡

| WO | Задача | Findings | Файлы (scope) |
|---|---|---|---|
| [WO-REL-1](../../governance/archive/workorders/WO-REL-1-atomic-timer.md) | Атомарный timer-fire, защита от дублей | R-1, R-2 | `TimerScheduler.java`, `DBServiceImpl.markTimerJobFired` |
| [WO-REL-2](../../governance/archive/workorders/WO-REL-2-outbox.md) | Transactional outbox для service-task | R-1, GAP-7 | `ServiceTaskEnqueueServiceImpl.java`, новый `outbox` changeset |
| [WO-REL-3](../../governance/archive/workorders/WO-REL-3-cache.md) | Eviction для BpmnServiceImpl cache | R-4 | `BpmnServiceImpl.java` |

---

## Фаза 2 — Security Hardening (усиление безопасности) 🟡

| WO | Задача | Findings | Файлы (scope) |
|---|---|---|---|
| [WO-SEC-4](../../governance/archive/workorders/WO-SEC-4-login-ratelimit.md) | Rate-limit на `/auth/login` | HIGH-3 | `AuthResource.java`, новый filter |
| [WO-SEC-5](../../governance/archive/workorders/WO-SEC-5-assignee-check.md) | Проверка assignee при complete user-task | HIGH-6 | `RuntimeResource.java`, `ActivityServiceImpl.java` |
| [WO-SEC-6](../../governance/archive/workorders/WO-SEC-6-frontend-auth.md) | JWT в httpOnly cookie + role guard на роутах | HIGH-2, MEDIUM-5 | `auth.ts`, `router.ts`, `AuthResource.java` |
| [WO-SEC-7](../../governance/archive/workorders/WO-SEC-7-pii-logging.md) | Убрать PII из логов FEEL | MEDIUM-3 | `ScriptServiceImpl.java` |

---

## Фаза 3 — Feature Completeness (полнота функционала) 🟢

| WO | Задача | Findings | Файлы (scope) |
|---|---|---|---|
| [WO-FEAT-1](../../governance/archive/workorders/WO-FEAT-1-dmn-hit-policies.md) | DMN: COLLECT/RULE ORDER/PRIORITY/OUTPUT ORDER | GAP-2 | `DmnServiceImpl.java` |
| [WO-FEAT-2](../../governance/archive/workorders/WO-FEAT-2-cancel-api.md) | Cancel/terminate API для process instance | GAP-6 | `RuntimeContract.java`, `ActivityServiceImpl.java` |
| [WO-FEAT-3](../../governance/archive/workorders/WO-FEAT-3-bounded-timer.md) | Bounded repeating timers `R<n>/PT` | GAP-4 | `TimerExpressions.java`, `TimerScheduler.java` |
| [WO-FEAT-4](../../governance/archive/workorders/WO-FEAT-4-token-refresh.md) | Refresh token + revocation | MEDIUM-2 | `TokenService.java`, `AuthResource.java` |

---

## Фаза 4 — Architecture (ADR required) 🔵

Требуют ADR перед кодом.

| ADR | Тема | Findings | Почему ADR |
|---|---|---|---|
| ADR-1 | Replace hand-rolled JWT → JJWT/Nimbus | HIGH-1 | Замена crypto-примитива |
| ADR-2 | Leader election для timer-scheduler | R-2 | Инфраструктурное решение (ShedLock vs DB advisory lock) |
| ADR-3 | Тесты на реальном PostgreSQL (Testcontainers) | GAP-10 | Меняет весь test-stack |
| ADR-4 | Multi-tenancy scope | future | Структурное изменение схемы |

---

## Governance

- Mimo пишет код в feature-ветках, открывает PR
- Claude (CTO) ревьюет по диску (grep/find/run), мержит в master
- Каждый WO: обязательный proof-of-failure до фикса (V3), proof-of-passing после
- Структурные изменения (ADR-уровень) → ADR сначала, код потом (V10)
- Ни один WO не считается выполненным без BUILD SUCCESS + IT зелёных

---

## Что НЕ меняем без ADR

- Схема JWT (формат токена, claims)
- Структура RabbitMQ exchange/routing
- Схема DB (только через Liquibase changeset)
- `zorrobpm-contract` (ломает Java-клиентов)
