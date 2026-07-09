# WO-FEAT-2 — Cancel / Terminate Process Instance API     [P3 / GAP-6]

## Цель
Дать операторам возможность программно останавливать зависшие или ненужные инстансы.

## Проблема
В `RuntimeContract` нет ни `cancel`, ни `terminate` операции. Остановить инстанс можно только
через БД напрямую или через indirect incident management.

## Scope
**Можно трогать:** `RuntimeContract.java`, `RuntimeResource.java`, `ActivityServiceImpl.java`
(логика отмены токенов/активностей), новый Liquibase changeset если нужно.  
**Нельзя:** менять схему существующих таблиц; удалять данные о завершённых инстансах.

## Семантика
- `POST /process-instances/{id}/cancel` — graceful: завершить все активные активности со статусом
  `CANCELLED`, токены удалить, инстанс → `CANCELLED`. Компенсация НЕ запускается.
- Уже COMPLETED/CANCELLED инстансы → 409 CONFLICT (idempotency check).

## Критерии приёмки

| # | Критерий | Команда проверки | Ожидаемый факт |
|---|---|---|---|
| 1 | ACTIVE инстанс → cancel → CANCELLED | IT: start process → POST /cancel | process_instance.status = CANCELLED |
| 2 | Все active activities → CANCELLED | IT: процесс с 2 user-tasks → cancel | обе задачи CANCELLED |
| 3 | COMPLETED инстанс → cancel → 409 | IT: cancel завершённого | HTTP 409 |
| 4 | Уже CANCELLED → cancel → 409 | IT: двойной cancel | HTTP 409 |
| 5 | **proof-of-failure (V3)** | запрос `POST /process-instances/{id}/cancel` на текущем коде | 404 (эндпоинт не существует) |
| 6 | Регресс | `mvn clean verify` | BUILD SUCCESS |

## Запреты
Не удалять data из БД (только статусы). Не запускать компенсацию автоматически.
Изменение `RuntimeContract` — проверить с CTO на предмет breaking change.

## DoD / отчёт
Таблица критерий → команда → факт. Proof-of-failure #5.
