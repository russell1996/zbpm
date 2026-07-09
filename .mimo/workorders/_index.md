# Work Orders — индекс

> Брать строго по приоритету. Одна задача одновременно (V8).  
> Правила: [rules.md](../rules/rules.md) | Роадмап: [docs/README.md](../../docs/README.md)

## Фаза 0 — Production Blockers 🔴 (блокируют прод)

| WO | Задача | Severity | Владелец |
|---|---|---|---|
| [WO-SEC-1](WO-SEC-1-api-auth.md) | Аутентификация всех Data API | CRITICAL | security-architect |
| [WO-SEC-2](WO-SEC-2-hardening.md) | CORS + default secret + admin/admin hardening | CRITICAL | security-architect |
| [WO-SEC-3](WO-SEC-3-validation-errors.md) | DTO validation + GlobalExceptionHandler | HIGH | senior-engineer |

## Фаза 1 — Reliability 🟡

| WO | Задача | Severity | Владелец |
|---|---|---|---|
| [WO-REL-1](WO-REL-1-atomic-timer.md) | Атомарный timer-fire | HIGH | scheduler-architect |
| [WO-REL-2](WO-REL-2-outbox.md) | Transactional outbox для service-task | HIGH | persistence-architect |
| [WO-REL-3](WO-REL-3-cache.md) | BpmnServiceImpl cache eviction | MEDIUM | senior-engineer |

## Фаза 2 — Security Hardening 🟡

| WO | Задача | Severity | Владелец |
|---|---|---|---|
| [WO-SEC-4](WO-SEC-4-login-ratelimit.md) | Rate-limit на /auth/login | HIGH | security-architect |
| [WO-SEC-5](WO-SEC-5-assignee-check.md) | Assignee check при complete | HIGH | senior-engineer |
| [WO-SEC-6](WO-SEC-6-frontend-auth.md) | JWT httpOnly cookie + role guard | HIGH | senior-engineer |
| [WO-SEC-7](WO-SEC-7-pii-logging.md) | Убрать PII из логов | MEDIUM | senior-engineer |

## Фаза 3 — Feature Completeness 🟢

| WO | Задача | Findings | Владелец |
|---|---|---|---|
| [WO-FEAT-1](WO-FEAT-1-dmn-hit-policies.md) | DMN hit policies COLLECT/RULE ORDER | GAP-2 | feel-dmn-architect |
| [WO-FEAT-2](WO-FEAT-2-cancel-api.md) | Cancel/terminate process instance | GAP-6 | execution-engine-architect |
| [WO-FEAT-3](WO-FEAT-3-bounded-timer.md) | Bounded repeating timers R&lt;n&gt; | GAP-4 | scheduler-architect |
| [WO-FEAT-4](WO-FEAT-4-token-refresh.md) | Refresh token + revocation | MEDIUM-2 | security-architect |
