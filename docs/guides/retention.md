# Ретенция (retention): что чистится, дефолты, включение, откат

Фон: таблицы `events` (лента доменных событий, курсор — `feed_position`) и
`outbox` (transactional outbox) растут без границы — чистка завершённых
инстансов их не касается. WO-AUDIT-7 добавил два батчевых прохода в
`RetentionJob` (рядом с историческим instance/submission/orphan-проходом).

## Что чистится

- **`events`** (`RetentionBatchProcessor.claimAndDeleteEventsBatch`):
  назначенные (`feed_position IS NOT NULL`) строки старше
  `events-ttl-days`, кроме трёх классов, которые НЕ удаляются никогда:
  1. строки с позицией **выше минимального активного SSE-курсора**
     (транзитный пин `SseLiveCursorTracker`: REST трекает курсор catchup'а
     клиента до чтения, двигает на drain/live-рассылке, снимает на
     disconnect; сессия ограничена таймаутом эмиттера — пин не залипает);
  2. строки **без позиции** (`NULL` — джоб `FeedPositionAssigner` ещё не
     назначил, consumer их не видел);
  3. строка с **текущей максимальной позицией** (якорь монотонности
     счётчика assigner'а: пустая таблица обнуляет `MAX`, и следующий тик
     назначил бы уже выданные позиции заново — дубль в ленте).
- **`outbox`** (`claimAndDeleteOutboxBatch`): терминальные записи старше
  `outbox-ttl-days` — опубликованные (`published = true`, любым статусом)
  и карантинные (`status = 'FAILED'`). Активные/ожидающие/ретраящиеся
  (`published = false AND status = 'PENDING'`) предикат **не видит ни при
  каком TTL** — их забирает только поллер.

Оба прохода: малые батчи (`batch-size`), `SELECT … FOR UPDATE SKIP LOCKED`
в той же транзакции, что `DELETE` (две реплики не видят одни строки),
дедлайн прохода — между батчами (`pass-budget-ms`, прогресс коммитится по
ходу). Метрики: `zbpm.retention.events.deleted.total`,
`zbpm.retention.outbox.deleted.total`, `zbpm.retention.pass.duration`.

## Дефолты и включение

Всё выключено по умолчанию (`events-ttl-days=0`, `outbox-ttl-days=0` —
поведение не меняется). Включение:

```bash
ZORROBPM_ENGINE_RETENTION_EVENTS_TTL_DAYS=30
ZORROBPM_ENGINE_RETENTION_OUTBOX_TTL_DAYS=30
```

Имена переменных — точная relaxed-binding форма ключей (урок WO-C8-36 F-7:
укороченное имя не разрешается). Остальные ручки:
`ZORROBPM_ENGINE_RETENTION_DRY_RUN` (только считает и логирует «удалил бы
N», покрывает ТОЛЬКО эти два прохода), `ZORROBPM_ENGINE_RETENTION_BATCH_PAUSE_MS`
(пауза между батчами, 0 = без паузы).

## Откат

Выключить — вернуть оба TTL в 0 и перезапустить (проходы пропускаются в
начале `run()`). Удаление необратимо само по себе (строки не
восстанавливаются), но dry-run показывает объём до включения. Новых
миграций WO не вводил — откат схемы не нужен.
