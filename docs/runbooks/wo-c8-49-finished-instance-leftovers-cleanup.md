# Зачистка открытых инцидентов и ERROR-активностей на завершённых инстансах (WO-C8-49, п.3)

**СТАТУС: ПРЕДЛОЖЕНИЕ. НЕ ИСПОЛНЯТЬ без явного «да» владельца.**
Liquibase-changeset = стоп-список G-C (решение о миграции — maintainer); прод-запись — зона владельца.
Этот документ — read-only отчёт-перечень (запросы) + предложение скрипта, что требует п.3 WO.
НОВЫЕ утечки с момента мержа WO-C8-49 закрывает движок сам (единая финализация инстанса в
kill-путях: cancel, terminate (root), kill/вылет дочерних инстансов) — скрипт чистит только
ДОВОЛНОВОЙ бэклог.

## Факты (maintainer, read-only SELECT, прод, 2026-10-14)

- Открытых инцидентов на ЗАВЕРШЁННЫХ инстансах: **70** (из них 56 на отменённых).
- ERROR-активностей на завершённых инстансах: **53**.
- Всего открытых инцидентов: 267.
- Пример: инстанс `9dcdd13e-05f7-419c-8f1c-45cdf8c65346` — инцидент на MI-входе
  (`inputCollection did not evaluate to a list`, данные воркера), инстанс отменён оператором;
  потом на инцидент закрытого инстанса нажали resolve → зомби-активность (до WO-C8-49 resolve
  перезапускал элемент; теперь guard 409).

## 1. Read-only отчёт-перечень (ids, instance, element, возраст)

Только SELECT, ни одной записи. Запросы дают текущую картину (числа на 2026-10-14 могли
дрейфнуть — снять заново перед решением).

```sql
-- 1a. Открытые инциденты на завершённых инстансах
SELECT i.id                AS incident_id,
       pi.id               AS process_instance_id,
       pi.cancelled        AS instance_cancelled,
       pi.completed_at     AS instance_finished_at,
       a.bpmn_element_id   AS element,
       i.created_at        AS incident_created_at,
       age(now(), i.created_at) AS incident_age,
       left(i.message, 200)    AS message
FROM incidents i
JOIN activities a           ON a.id = i.activity_id
JOIN process_instances pi   ON pi.id = a.process_instance_id
WHERE i.completed_at IS NULL
  AND pi.completed_at IS NOT NULL
ORDER BY i.created_at;

-- 1b. ERROR-активности на завершённых инстансах
SELECT a.id                AS activity_id,
       pi.id               AS process_instance_id,
       pi.cancelled        AS instance_cancelled,
       pi.completed_at     AS instance_finished_at,
       a.bpmn_element_id   AS element,
       a.created_at        AS activity_created_at,
       age(now(), a.created_at) AS age
FROM activities a
JOIN process_instances pi  ON pi.id = a.process_instance_id
WHERE a.status = 'ERROR'
  AND pi.completed_at IS NOT NULL
ORDER BY a.created_at;
```

## 2. Предложенный скрипт (исполнить только после «да» владельца)

Семантика — та же, что финализация движка в kill-путях (не копия другой логики):
открытые инциденты инстанса получают `completed_at = now()` (статус «закрыт» — единая модель
`completed_at IS NOT NULL`), ERROR-активности переходят в терминальное `CANCELLED` с
`completed_at` (тот же помечатель, что `cancelActivity`).

Осознанно БЕЗ доменных событий (`incident.resolved` не эмитится): данные исторические,
уведомлять SSE/ленту о закрытии инцидентов давних завершённых инстансов не нужно;
in-memory-гейдж stuck-токенов сбрасывается при рестарте приложения.

Скрипт идемпотентен (второй прогон задевает 0 строк). Сначала dry-run-подсчёт, потом запись
в одной транзакции.

```sql
\set ON_ERROR_STOP on
BEGIN;

-- 2a. Dry-run: сколько строк будет затронуто (согласовать с п.1 перед COMMIT)
SELECT 'incidents' AS what, count(*) FROM incidents i
JOIN activities a           ON a.id = i.activity_id
JOIN process_instances pi   ON pi.id = a.process_instance_id
WHERE i.completed_at IS NULL AND pi.completed_at IS NOT NULL
UNION ALL
SELECT 'error_activities', count(*) FROM activities a
JOIN process_instances pi   ON pi.id = a.process_instance_id
WHERE a.status = 'ERROR' AND pi.completed_at IS NOT NULL;

-- 2b. Закрыть инциденты завершённых инстансов
UPDATE incidents i
SET completed_at = now()
WHERE i.completed_at IS NULL
  AND i.id IN (
    SELECT i2.id FROM incidents i2
    JOIN activities a           ON a.id = i2.activity_id
    JOIN process_instances pi   ON pi.id = a.process_instance_id
    WHERE pi.completed_at IS NOT NULL
  );
-- Ожидается: ROW_COUNT = числу из 2a (incidents)

-- 2c. ERROR -> CANCELLED (терминальная пометка, как cancelActivity)
UPDATE activities a
SET status = 'CANCELLED', completed_at = now()
WHERE a.status = 'ERROR'
  AND a.id IN (
    SELECT a2.id FROM activities a2
    JOIN process_instances pi ON pi.id = a2.process_instance_id
    WHERE pi.completed_at IS NOT NULL
  );
-- Ожидается: ROW_COUNT = числу из 2a (error_activities)

-- 2d. Контрпроверка: остаток должен быть 0/0
SELECT (SELECT count(*) FROM incidents i
        JOIN activities a           ON a.id = i.activity_id
        JOIN process_instances pi   ON pi.id = a.process_instance_id
        WHERE i.completed_at IS NULL AND pi.completed_at IS NOT NULL) AS open_incidents_left,
       (SELECT count(*) FROM activities a
        JOIN process_instances pi   ON pi.id = a.process_instance_id
        WHERE a.status = 'ERROR' AND pi.completed_at IS NOT NULL) AS error_activities_left;

COMMIT;
```

## 3. Порядок выката

1. Владелец даёт «да» (пересняв п.1 — число и состав строк актуальны).
2. Dry-run (2a) на проде read-only: сверить ROW_COUNT'ы с п.1.
3. Прогнать 2b–2d одной транзакцией (`psql -f` или плейн-сессия); при несовпадении чисел —
   ROLLBACK, разбираться, не COMMIT'ить.
4. После выката: контрольный запрос п.1 → 0 строк; открытые инциденты в сумме = 267−70
   (на живых инстансах, это штатно).

Если владелец предпочтёт зачистку Liquibase-миграцией — это отдельное решение maintainer (G-C,
changeset с rollback); в этом WO changeset намеренно НЕ заведён.
