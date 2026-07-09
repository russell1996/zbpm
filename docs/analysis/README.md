# ZorroBPM CE — As-Is Analysis Index

> Анализ проведён по исходному коду. Каждая запись содержит файл:строку.  
> Severity: CRITICAL → HIGH → MEDIUM → LOW

## Сводная таблица находок

| ID | Severity | Категория | Краткое описание | Файл / Деталь |
|---|---|---|---|---|
| **CRITICAL-1** | CRITICAL | Security | CORS wildcard + `allowCredentials(true)` | [01-security §C1](01-security.md#critical-1) |
| ~~**CRITICAL-2**~~ | ✅ FIXED | Security | ~~Нет аутентификации на 90% API-эндпоинтов~~ — `requireApiAuth=true` + `isDataApiPath()` уже в коде | [01-security §C2](01-security.md#critical-2) |
| **CRITICAL-3** | CRITICAL | Security | Дефолтные `admin/admin` + JWT-секрет `change-me-...` | [01-security §C3](01-security.md#critical-3) |
| **HIGH-1** | HIGH | Security | Hand-rolled JWT — риск регрессии | [01-security §H1](01-security.md#high-1) |
| **HIGH-2** | HIGH | Security | JWT хранится в `localStorage` — украдётся при XSS | [01-security §H2](01-security.md#high-2) |
| **HIGH-3** | HIGH | Security | Нет rate-limit на `/auth/login` — brute-force | [01-security §H3](01-security.md#high-3) |
| **HIGH-4** | HIGH | Security | `@SneakyThrows` — стектрейс утекает в 500-ответ | [01-security §H4](01-security.md#high-4) |
| **HIGH-5** | HIGH | Security/Rel | Нет `@Valid` на DTO — нет валидации входных данных | [01-security §H5](01-security.md#high-5) |
| **HIGH-6** | HIGH | Security | Нет проверки assignee при complete user-task | [01-security §H6](01-security.md#high-6) |
| **MEDIUM-1** | MEDIUM | Security | Нет CSRF-защиты | [01-security §M1](01-security.md#medium-1) |
| **MEDIUM-2** | MEDIUM | Security | Токены 12 ч, без отзыва, без refresh | [01-security §M2](01-security.md#medium-2) |
| **MEDIUM-3** | MEDIUM | Security | PII-переменные логируются на INFO | [01-security §M3](01-security.md#medium-3) |
| **MEDIUM-4** | MEDIUM | Security | Роль — свободная строка, нет DB constraint | [01-security §M4](01-security.md#medium-4) |
| **MEDIUM-5** | MEDIUM | Security | Роут `/admin/users` не защищён в роутере фронта | [01-security §M5](01-security.md#medium-5) |
| **R-1** | HIGH | Reliability | Потеря service-task при сбое RabbitMQ afterCommit | [02-reliability §R1](02-reliability.md#r-1) |
| **R-2** | MEDIUM | Reliability | Timer-scheduler без distributed lock — N-кратный fire | [02-reliability §R2](02-reliability.md#r-2) |
| **R-3** | LOW | Reliability | `markTimerJobFired` не атомарно с fire | [02-reliability §R3](02-reliability.md#r-3) |
| **R-4** | MEDIUM | Reliability | `BpmnServiceImpl` кеш не evict-ится — OOM | [02-reliability §R4](02-reliability.md#r-4) |
| **R-5** | MEDIUM | Reliability | `setVariables` TOCTOU под concurrent writers | [02-reliability §R5](02-reliability.md#r-5) |
| **R-6** | MEDIUM | Reliability | `DMN listDecisions()` — весь каталог в память | [02-reliability §R6](02-reliability.md#r-6) |
| **R-7** | LOW | Reliability | Parallel gateway join с root token → NPE | [02-reliability §R7](02-reliability.md#r-7) |
| **R-8** | MEDIUM | Performance | N+1: `getProcessInstance()` на каждый flow-переход | [02-reliability §R8](02-reliability.md#r-8) |
| **R-9** | LOW | Reliability | Переменные пишутся дважды при старте инстанса | [02-reliability §R9](02-reliability.md#r-9) |
| **GAP-1** | — | Gap | API уязвим — 90% без auth (дубль CRITICAL-2) | [03-gaps §G1](03-gaps.md#g-1) |
| **GAP-2** | — | Gap | DMN: COLLECT / RULE ORDER / OUTPUT ORDER не реализованы | [03-gaps §G2](03-gaps.md#g-2) |
| **GAP-3** | — | Gap | Вложенные event sub-processes не поддержаны | [03-gaps §G3](03-gaps.md#g-3) |
| **GAP-4** | — | Gap | Bounded repeating timer (`R<n>/PT`) — только первое срабатывание | [03-gaps §G4](03-gaps.md#g-4) |
| **GAP-5** | — | Gap | Targeted compensation / compensation в subprocess | [03-gaps §G5](03-gaps.md#g-5) |
| **GAP-6** | — | Gap | Нет cancel / terminate API для process instance | [03-gaps §G6](03-gaps.md#g-6) |
| **GAP-7** | — | Gap | Нет transactional outbox для service-task | [03-gaps §G7](03-gaps.md#g-7) |
| **GAP-8** | — | Gap | Нет global `@RestControllerAdvice` — opaque 500 | [03-gaps §G8](03-gaps.md#g-8) |
| **GAP-9** | — | Gap | DMN `listDecisions()` без пагинации | [03-gaps §G9](03-gaps.md#g-9) |

## Покрытие тестами

| Компонент | Тест | Пробел |
|---|---|---|
| Engine integration | 55+ IT в `zorrobpm-engine/test/.../integration/` | — |
| TokenService / PasswordHasher | Unit tests есть | — |
| JwtAuthFilter | **Нет тестов** | не проверяется, какие пути защищены |
| WebConfiguration CORS | **Нет тестов** | permissive CORS не проверяется |
| UiUserBootstrap | **Нет тестов** | дефолтный admin не тестируется |
| UiUserServiceImpl (login) | **Нет тестов** | логин не тестируется на HTTP-уровне |
| AuthResource | **Нет REST IT** | эндпоинт логина без интеграционного теста |
| UserResource | **Нет REST IT** | CRUD пользователей без HTTP-тестов |
| Timer lost-job | **Нет теста** | нет теста на сбой RabbitMQ при afterCommit |
| DB: H2 vs PostgreSQL | Тесты на H2 | лок/JSONB/UUID — расхождения возможны |
