# Work Orders — готовые задачи в работу

Готовые задачи по [роадмапу](../../docs/README.md) §3, оформленные по [task-prompt](../prompts/task-prompt.md):
цель, объективные критерии (V2/V3), запреты (V4/V8). Брать строго по приоритету. Одна задача за раз (V8).

## Фаза 1 — блокеры прода (P0)
| Work order | Задача | Код наблюдения | Владелец-роль |
|---|---|---|---|
| [WO-T1](WO-T1-data-api-auth.md) | Защита Data API (аутентификация на всех эндпоинтах) | SEC1/T1 | security-architect |
| [WO-T4](WO-T4-security-hardening.md) | Security hardening (secret/CORS/admin) | SEC2-4/T4 | security-architect |
| [WO-T2](WO-T2-atomic-timer-fire.md) | Атомарный timer-fire + защита от дублей | F1/F4/T2 | scheduler-architect |

## Дальше (по роадмапу)
T3 (версионирование), T8 (observability/индексы), T7 (assignee/formKey), T6 (DMN hit policies),
U1 (timers/messages off mock), затем HA-трек (ADR: async continuation, outbox, leader election).
Эти оформляются в work order по мере подхода очереди — по тому же шаблону.

> Перед взятием: прочитать [rules](../rules/rules.md) (V1–V10) и [conventions](../standards/conventions.md).
> Структурное изменение → сначала ADR (V10), не код.
