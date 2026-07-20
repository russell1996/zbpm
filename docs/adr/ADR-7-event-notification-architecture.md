# ADR-7 — Архитектура уведомлений о событиях (event notification)

Статус: **ПРИНЯТ** (2026-07-19). Автор: CTO/Architect.
Связь: [ADR-1 (multi-tenant authz)](ADR-1-multi-tenant-authorization.md), [ADR-2 (control-plane / API-ключи)](ADR-2-centralized-control-plane.md).

## Проблема
Внешние **системы** (сервер↔сервер) и внешние **UI** (браузер) должны узнавать, что в движке что-то изменилось
(инстанс завершился/отменён, появилась/закрыта user-task, поднялся/разрешён инцидент), **без поллинга «в лоб»**.

## Что НЕ так с «встроить WebSocket в движок»
Движок — stateless и headless («за вашей системой», per-system кода нет). WebSocket-сессии — stateful, завязаны на
auth, плохо масштабируются, привязывают ядро к UI-транспорту. Camunda 8 браузеру по WebSocket НЕ пушит: Zeebe
**экспортирует события** в поток, а Operate/Tasklist (отдельные приложения) их читают. **Правильный шов — эмитить
события; транспорт к браузеру — забота BFF/фронта.**

## Решение: событие как источник истины + 3 контракта потребления

### Слой 0 — Фундамент: надёжный поток доменных событий
- В точках изменения состояния (DBService: createProcessInstance/completeProcessInstance/cancelProcessInstance,
  createActivity/completeActivity, createUserTask/completeUserTask, createServiceTask, createIncident/resolveIncident)
  движок пишет **доменное событие в транзакционный outbox В ТОЙ ЖЕ ТРАНЗАКЦИИ**, что и изменение (переиспользуем
  `OutboxEntry`/`OutboxBatchProcessor`, at-least-once, poller уже SKIP-LOCKED-safe, AUD-1).
- Событие также сохраняется в **таблицу `events`** (append-only, монотонный `sequence BIGINT`) — это источник для
  pull-API (Контракт B) и одновременно аудит-история.

**Envelope (публичный контракт, версионируемый):**
```json
{
  "sequence": 1024,                     // монотонный курсор (глобальный порядок)
  "id": "uuid",                         // идемпотентность/dedupe
  "type": "process-instance.completed", // см. каталог типов
  "version": 1,
  "occurredAt": "2026-07-19T10:00:00Z",
  "processDefinitionKey": "vacation",
  "processDefinitionId": "uuid",
  "processInstanceId": "uuid",
  "elementId": "Activity_x",            // где применимо
  "ownerScope": "<processDefinitionId>",// для authz-фильтра (ADR-2 grants)
  "data": { ... }                        // тип-специфичная нагрузка
}
```
Каталог типов (стартовый): `process-instance.started|completed|cancelled`, `activity.completed`,
`user-task.created|completed`, `service-task.created`, `incident.raised|resolved`.

### Контракт A — AMQP (для систем с RabbitMQ): topic-exchange `zorrobpm.events`
Outbox-poller публикует envelope в **topic-exchange** `zorrobpm.events` с routing-key = `type` (`incident.raised`,
`user-task.created`, …). Потребитель биндит очередь с нужными паттернами. Durable, multi-consumer, scale-out-safe.

### Контракт B — HTTP pull (универсальный, firewall-friendly): `GET /events`
`GET /events?since=<sequence>&type=&processInstanceId=&processDefinitionKey=&limit=` — курсор-пагинация по
`sequence`. Для ЛЮБОЙ внешней системы/UI, кто не хочет AMQP (большинство): «дай всё, что случилось после курсора».
Реплеится (курсор в БД). **Authz: фильтр по grants принципала (ADR-2)** — потребитель видит только события своих
process-definition (full-access — все). Никаких кросс-тенант утечек.

### Контракт C — HTTP push (SSE) для внешних UI и встроенного SPA: `GET /events/stream`
`GET /events/stream?type=&processInstanceId=&processDefinitionKey=` — **Server-Sent Events** (не WebSocket:
одностороннее «фронт принимает изменения» = ровно SSE; HTTP + JWT-cookie + nginx без апгрейд-возни; авто-реконнект;
`Last-Event-ID` = sequence для докачки пропущенного). JWT-auth, тот же authz-фильтр по grants. В каждой реплике
SSE-мост подписан на `zorrobpm.events` (эксклюзивная очередь) → пушит своим подключённым клиентам (multi-replica-safe).

## Гарантии и правила
- **At-least-once**, НЕ exactly-once. Потребитель идемпотентен: dedupe по `id`/`sequence`.
- **Порядок**: монотонный `sequence` (глобальный); в рамках одного `processInstanceId` порядок сохранён.
- **Докачка**: и B (`since=cursor`), и C (`Last-Event-ID`) позволяют потребителю догнать пропущенное после разрыва.
- **Ретенция** таблицы `events` — переиспользовать паттерн REL-7 (TTL, дефолт разумный), чтобы не росла бесконечно.
- **Authz** (ADR-1/2): B и C ОБЯЗАНЫ фильтровать по grants принципала. AMQP (A) — доверенная инфра (внутр. периметр),
  либо позже per-tenant routing-keys.
- **Security-конфиг** (G-C): новые эндпоинты B/C и WS/SSE — только с full-context authz-тестом (V11).

## Почему так, а не иначе (отклонённые варианты)
- **WebSocket в движок** — отклонено: stateful-сессии в stateless-ядре, не как в C8. WS/SSE-транспорт — в web-слое/BFF.
- **Только AMQP** — отклонено: многие внешние системы/UI не говорят на AMQP (firewall, нет клиента) → нужен HTTP (B/C).
- **Только webhooks (движок POST-ит потребителю)** — отложено (Phase 3): нужна регистрация URL, ретраи, HMAC-подпись,
  идемпотентность; большой отдельный кусок. Pull (B) + SSE (C) закрывают потребности проще и надёжнее.

## Фазы (пул работ)
- **EVT-1** (engine): event-модель + envelope + таблица `events` + эмиссия в outbox на сеймах DBService. Фундамент.
- **EVT-2** (rabbitmq): publisher outbox → topic-exchange `zorrobpm.events` (Контракт A).  ∥ EVT-3.
- **EVT-3** (rest): `GET /events?since=cursor` pull-API + authz-фильтр (Контракт B).  ∥ EVT-2.
- **EVT-4** (rest): SSE `GET /events/stream` + JWT-auth + authz-фильтр + мост от exchange (Контракт C).
- **EVT-5** (frontend): SSE-клиент + интеграция Pinia-stores (task/process/incident).
- **EVT-6** (опц., позже): webhooks (регистрация + ретраи + HMAC).
