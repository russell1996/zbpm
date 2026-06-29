# 07 — API & Integrations (as-is)

## REST API
Контракты — в `zorrobpm-contract` как HTTP-интерфейсы (`@GetExchange`/`@PostExchange`), реализуются
контроллерами в `zorrobpm-rest/resource/` (тонкие, `@Transactional`, делегируют в сервисы):
| Resource | Назначение |
|---|---|
| `RuntimeResource` | start instance, complete/fail service task, complete user task, resolve incident |
| `QueryResource` | пагинированные запросы: instances/tasks/incidents/variables + single-entity GET + `/process-instances/{id}/activities` + `/timer-jobs` + `/message-subscriptions` |
| `ProcessDefinitionResource` | CRUD определений, XML, структура |
| `DmnResource` | `/dmn`, `/dmn/{id}`, `/dmn/{id}/evaluate` |
| `AuthResource` / `UserResource` | `/auth/me`, `/users/**` (UI-логин) |

Контракты переиспользуются `zorrobpm-client` (Java SDK) — тот же интерфейс, что и сервер (DRY, **P1**).

### Безопасность API — **SEC1/T1**
`JwtAuthFilter.isProtected` [:30] защищает **только** `/auth/me` и `/users/**` (последний — роль ADMIN).
Все остальные (`/process-*`, `/incidents`, `/variables`, `/dmn`, `/timer-jobs`, `/message-subscriptions`) —
**открыты без аутентификации**. Подробности и остальные SEC — в [08](08-cluster-ha-security.md).

## RabbitMQ интеграция
- **`zorrobpm-rabbitmq`** слушает доменные события (`@EventListener`) и мостит в брокер; engine не знает о брокере (**P1/P7**).
- **Поток job:** engine `ServiceTaskEnqueued` (afterCommit) → `ServiceTaskListener` → очередь `zorrobpm.jobs.<jobType>`
  (per-type, авто-декларация) → воркер → completion в `zorrobpm.complete-service-task` (с DLQ) →
  `ServiceTaskListener` → `ServiceTaskCompleted` → engine `completeServiceTask`.
- **At-least-once:** брокер может редоставить → идемпотентность завершения (**P2**) обязательна и присутствует.
- **F2/F3:** мост engine→MQ — `afterCommit` в памяти (`ServiceTaskEnqueueServiceImpl`), без transactional outbox;
  потерянный enqueue не восстанавливается (нет ресвипа). См. [05](05-incidents-compensation.md).

## Worker SDK
`zorrobpm-job-handler-spring-boot-starter`: интерфейс `JobHandler` (`getJob()` + `handleJob()`) +
`HandlerAutoConfiguration` — Spring Boot starter, воркер подключается зависимостью и реализует обработчик
по `jobType`. Завершение/ошибка job → REST/MQ обратно в engine (`completeServiceTask`/`failServiceTask`).

## Интеграционные свойства
- **Границы (V7):** модули общаются через контракты/события, не импортируют internal друг друга. ✅
- **Идемпотентность:** на пути completion — да (**P2**). На пути enqueue — частично (**F2**).
- **Ошибки не глотаются:** исключения воркера → `failServiceTask` → ретраи/инцидент (**P4**).

## Frontend↔Backend
SPA через относительный `/api` (nginx reverse-proxy). Часть разделов (timers/messages/users/dmn) исторически
на моках; backend read-API для timers/messages/dmn **уже есть** — остаётся фронт-проводка (U1 роадмапа).
