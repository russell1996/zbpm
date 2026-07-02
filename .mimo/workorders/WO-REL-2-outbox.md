# WO-REL-2 — Transactional Outbox для service-task enqueue     [P1 / HIGH]

## Цель
Устранить молчаливую потерю service-task при недоступности RabbitMQ в момент `afterCommit`.
Гарантировать доставку: если задача записана в БД — она будет отправлена воркеру.

## Проблема (точно по коду)

```
zorrobpm-engine/.../service/impl/ServiceTaskEnqueueServiceImpl.java:34-68
```
```java
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        publisher.publishEvent(new ServiceTaskEnqueued(detail));
        // если здесь упадёт или RabbitMQ недоступен → задача потеряна навсегда
    }
});
```
Нет retry, нет outbox, нет recovery для `CREATED` задач.

## Scope — строго
**Можно трогать:** `ServiceTaskEnqueueServiceImpl.java`, новая таблица `outbox` (Liquibase changeset №42),
новый `OutboxPollerService.java` (scheduled, @Profile("!test")), `ServiceTaskEnqueueServiceImplTest`.  
**Нельзя:** менять RabbitMQ exchange/routing/serialization; менять `zorrobpm-contract`; трогать движок за рамками enqueue.

## Решение (рекомендуемое)
Вариант A — Outbox pattern:
1. В той же транзакции вместо publish → `INSERT INTO outbox (payload, created_at, published=false)`
2. `OutboxPollerService` каждые 2 сек: `SELECT FOR UPDATE SKIP LOCKED WHERE published=false LIMIT 10`
3. Publish → `UPDATE outbox SET published=true`
4. Cleanup: удалять записи старше 7 дней

Вариант B — Recovery job:
1. Оставить `afterCommit` publish как есть
2. Добавить `@Scheduled` recovery: `SELECT activities WHERE type=SERVICE_TASK AND status=CREATED AND created_at < NOW()-5min`
3. Re-enqueue найденные задачи

**Выбрать Вариант A** — более надёжен. Если аргументы за B — эскалировать CTO (V10).

## Критерии приёмки

| # | Критерий | Команда проверки | Ожидаемый факт |
|---|---|---|---|
| 1 | RabbitMQ down при старте service-task | IT: заблокировать MQ в момент enqueue | задача в `outbox`, статус pending |
| 2 | После восстановления MQ → задача доставлена | IT: восстановить MQ → дождаться poller | воркер получает задачу, статус CREATED |
| 3 | Нормальный поток без сбоя MQ не сломан | существующие service-task IT | зелёные |
| 4 | Дубля нет при повторном poll | IT: задача в outbox → дважды запустить poller | воркер получает ровно 1 сообщение |
| 5 | **proof-of-failure (V3)** | воспроизвести сценарий #1 на ТЕКУЩЕМ коде | задача потеряна (не доставлена) |
| 6 | Регресс | `mvn clean verify` | BUILD SUCCESS |

## Запреты
Без `@Disabled`. Тест #1 должен реально блокировать MQ (mock или Testcontainers stop).
Не менять exchange/routing.

## Гейты
G3 + G4. CTO review.

## DoD / отчёт
Таблица критерий → команда → факт. Proof-of-failure #5 показан.
